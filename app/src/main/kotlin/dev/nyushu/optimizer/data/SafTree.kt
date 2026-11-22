package dev.nyushu.optimizer.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import dev.nyushu.optimizer.core.store.ConfigStore
import java.io.FileNotFoundException
import java.io.IOException

data class SafNode(val docId: String, val name: String, val isDir: Boolean)

/** Thin wrapper over DocumentsContract for one user-granted tree URI. */
class SafTree(private val resolver: ContentResolver, val treeUri: Uri) {
    private val rootId: String = DocumentsContract.getTreeDocumentId(treeUri)

    fun children(docId: String): List<SafNode> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val out = ArrayList<SafNode>()
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        resolver.query(uri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                out += SafNode(c.getString(0), c.getString(1) ?: "", c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR)
            }
        }
        return out
    }

    fun root(): SafNode = SafNode(rootId, "", true)

    /** Resolves a '/'-separated path below the root, or null when any segment is missing. */
    fun find(path: String): SafNode? {
        var cur = root()
        for (seg in path.split('/').filter { it.isNotEmpty() }) {
            cur = children(cur.docId).firstOrNull { it.name == seg } ?: return null
        }
        return cur
    }

    fun docUri(docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    fun ensureDir(path: String): SafNode {
        var cur = root()
        for (seg in path.split('/').filter { it.isNotEmpty() }) {
            val existing = children(cur.docId).firstOrNull { it.name == seg }
            cur = if (existing != null) existing else {
                val created = DocumentsContract.createDocument(resolver, docUri(cur.docId), DocumentsContract.Document.MIME_TYPE_DIR, seg)
                    ?: throw IOException("Could not create directory '$seg'")
                SafNode(DocumentsContract.getDocumentId(created), seg, true)
            }
        }
        return cur
    }

    /** Recursively lists file names (not directories) down to [maxDepth] levels. */
    fun walkFiles(maxDepth: Int, limit: Int = 5000): List<String> {
        val out = ArrayList<String>()
        fun go(id: String, depth: Int) {
            if (out.size >= limit) return
            for (n in children(id)) {
                if (n.isDir) { if (depth < maxDepth) go(n.docId, depth + 1) } else out += n.name
                if (out.size >= limit) return
            }
        }
        go(rootId, 0)
        return out
    }
}

/** ConfigStore over a SAF tree (the emulator's own DocumentsProvider). */
class SafConfigStore(private val resolver: ContentResolver, private val tree: SafTree) : ConfigStore {
    override fun read(path: String): String? {
        val node = tree.find(path)?.takeIf { !it.isDir } ?: return null
        return try {
            resolver.openInputStream(tree.docUri(node.docId))?.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: FileNotFoundException) { null }
    }

    override fun write(path: String, content: String) {
        val dir = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        val parent = tree.ensureDir(dir)
        val existing = tree.children(parent.docId).firstOrNull { it.name == name && !it.isDir }
        val uri = if (existing != null) tree.docUri(existing.docId) else
            DocumentsContract.createDocument(resolver, tree.docUri(parent.docId), "application/octet-stream", name)
                ?: throw IOException("Could not create '$name'")
        val out = resolver.openOutputStream(uri, "wt") ?: throw IOException("Could not open '$path' for writing")
        out.use { it.write(content.toByteArray(Charsets.UTF_8)); it.flush() }
    }

    override fun delete(path: String) {
        val node = tree.find(path) ?: return
        DocumentsContract.deleteDocument(resolver, tree.docUri(node.docId))
    }

    override fun list(dir: String): List<String> {
        val node = tree.find(dir)?.takeIf { it.isDir } ?: return emptyList()
        return tree.children(node.docId).map { it.name }.sorted()
    }
}
