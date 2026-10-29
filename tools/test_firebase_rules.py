"""Run against local Firebase emulators: firebase emulators:start --project demo-chinlearn.

Uses disposable emulator accounts and never contacts a production Firebase service.
"""
import base64
import json
import unittest
import urllib.error
import urllib.request
import uuid

AUTH = "http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1/accounts:signUp?key=fake-key"
DB = "http://127.0.0.1:8080/v1/projects/demo-chinlearn/databases/(default)/documents"


def request(url, method="GET", body=None, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, json.dumps(body).encode() if body is not None else None, headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        with error:
            return error.code, json.load(error)


class FirestoreRulesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        users = []
        for _ in range(2):
            code, body = request(AUTH, "POST", {
                "email": str(uuid.uuid4()) + "@example.test",
                "password": "Test-password-123", "returnSecureToken": True,
            })
            assert code == 200, body
            uid = body["localId"]
            code, verified = request(AUTH.replace("signUp", "update"), "POST", {
                "localId": uid, "emailVerified": True,
            }, "owner")
            assert code == 200, verified
            code, signed_in = request(AUTH.replace("signUp", "signInWithPassword"), "POST", {
                "email": body["email"], "password": "Test-password-123", "returnSecureToken": True,
            })
            assert code == 200, signed_in
            users.append((uid, signed_in["idToken"]))
        (cls.uid, cls.token), (_, cls.other_token) = users

    def manifest(self, **overrides):
        fields = {"schema": {"integerValue": "1"}, "chunks": {"integerValue": "1"}, "revision": {"stringValue": "test"}}
        fields.update(overrides)
        return {"fields": fields}

    def chunk(self, size=20):
        return {"fields": {"data": {"bytesValue": base64.b64encode(b"x" * size).decode()}}}

    @property
    def path(self):
        return DB + "/users/" + self.uid + "/sync/state"

    def test_owner_can_read_and_write_manifest_and_chunks(self):
        self.assertEqual(200, request(self.path, "PATCH", self.manifest(), self.token)[0])
        self.assertEqual(200, request(self.path, token=self.token)[0])
        self.assertEqual(200, request(self.path + "/chunks/0", "PATCH", self.chunk(), self.token)[0])
        self.assertEqual(200, request(self.path + "/chunks/0", token=self.token)[0])

    def test_other_account_cannot_read_or_write(self):
        for path, body in [(self.path, self.manifest()), (self.path + "/chunks/0", self.chunk())]:
            self.assertEqual(403, request(path, token=self.other_token)[0])
            self.assertEqual(403, request(path, "PATCH", body, self.other_token)[0])
            self.assertEqual(403, request(path, "DELETE", token=self.other_token)[0])

    def test_unverified_owner_cannot_read_or_write(self):
        code, body = request(AUTH, "POST", {
            "email": str(uuid.uuid4()) + "@example.test",
            "password": "Test-password-123", "returnSecureToken": True,
        })
        self.assertEqual(200, code)
        path = DB + "/users/" + body["localId"] + "/sync/state"
        for target, data in [(path, self.manifest()), (path + "/chunks/0", self.chunk())]:
            self.assertEqual(403, request(target, token=body["idToken"])[0])
            self.assertEqual(403, request(target, "PATCH", data, body["idToken"])[0])
            self.assertEqual(403, request(target, "DELETE", token=body["idToken"])[0])

    def test_signed_out_access_denied(self):
        self.assertEqual(403, request(self.path)[0])
        self.assertEqual(403, request(self.path, "PATCH", self.manifest())[0])

    def test_unknown_paths_denied(self):
        self.assertEqual(403, request(DB + "/users/" + self.uid + "/other/data", "PATCH", self.manifest(), self.token)[0])

    def test_invalid_manifest_denied(self):
        for fields in [
            {"schema": {"integerValue": "2"}}, {"chunks": {"integerValue": "0"}},
            {"chunks": {"integerValue": "41"}}, {"revision": {"stringValue": "x" * 101}},
            {"extra": {"stringValue": "unexpected"}},
        ]:
            self.assertEqual(403, request(self.path, "PATCH", self.manifest(**fields), self.token)[0], fields)

    def test_invalid_chunk_paths_and_sizes_denied(self):
        for chunk_id in ["40", "99", "-1", "name"]:
            self.assertEqual(403, request(self.path + "/chunks/" + chunk_id, "PATCH", self.chunk(), self.token)[0])
        self.assertEqual(403, request(self.path + "/chunks/0", "PATCH", self.chunk(200001), self.token)[0])
        self.assertEqual(200, request(self.path + "/chunks/39", "PATCH", self.chunk(200000), self.token)[0])


if __name__ == "__main__":
    unittest.main(verbosity=2)
