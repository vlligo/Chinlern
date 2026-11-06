package com.example.chinlern

/** Folder paths travel with each pack, so the complete hierarchy survives sync and backups. */
object FolderPaths {
    fun parent(path: String) = path.substringBeforeLast('/', "")
    fun title(path: String) = path.substringAfterLast('/')
    fun contains(folder: String, path: String) = path == folder || (folder.isNotEmpty() && path.startsWith("$folder/"))
    fun ancestors(path: String): List<String> = path.indices.filter { path[it] == '/' }.map { path.substring(0, it) }
    fun all(paths: List<String>): List<String> = PackOrder.sort(paths.filter { it.isNotEmpty() }
        .flatMap { ancestors(it) + it }.distinct().map { it to it }).map { it.first }
    fun depth(path: String) = if (path.isEmpty()) 0 else path.count { it == '/' }
    fun validate(path: String) {
        require(path.isEmpty() || (path.split('/').size <= 8 && path.split('/').all { it.isNotBlank() && it == it.trim() && it.length <= 80 })) {
            "До 8 уровней папок; название каждого уровня — от 1 до 80 знаков."
        }
    }
    fun child(parent: String, name: String): String {
        require(name.trim().length in 1..80 && '/' !in name) { "Название папки — от 1 до 80 знаков, без /." }
        return (if (parent.isEmpty()) name.trim() else "$parent/${name.trim()}").also(::validate)
    }
    fun replace(path: String, source: String, target: String): String =
        if (path == source) target else target + path.removePrefix(source)
}
