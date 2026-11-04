# Chinlearn 2.2.1

- Fixed an invalid review state when previously mastered skills with a correct-answer streak moved to the adaptive scheduler.
- Saved review states affected by that migration are repaired when read, preserving review dates, counters, and recent review history.
- Restored questions are checked against the current practice queue even when an answer is already displayed. Questions that are no longer eligible, including unavailable Hanping listening prompts after a voice change, are cleared.

Install this update over the existing app using the same signing key to keep local data. Export a full backup in Settings before updating.

**If you use cloud sync, update all your devices.** Versions before 2.2 cannot accept review intervals longer than 30 days.
