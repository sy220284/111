import unittest
from prepare_android_remote_delivery import CHANGES, EXEC_CHANGES, adapt, PROCESSOR, run
from pathlib import Path
import tempfile

class RemoteDeliveryAdapterTest(unittest.TestCase):
    def test_exact_and_idempotent(self):
        for _, old, new in CHANGES + EXEC_CHANGES:
            self.assertEqual(adapt(old, old, new), new)
            self.assertEqual(adapt(new, old, new), new)
            with self.assertRaises(ValueError):
                adapt(old + old, old, new)
            with self.assertRaises(ValueError):
                adapt("changed upstream", old, new)

    def test_rejects_local_and_symlinks_and_large_files(self):
        for text in ("LOCAL_ENVIRONMENT_ID", "join_descendant", "&root != workspace_uri",
                     "android_read_delivery_file", "metadata.is_symlink", "bytes.len() as u64 > MAX_BYTES"):
            self.assertIn(text, PROCESSOR)

    def test_dedicated_remote_method_and_read_bound(self):
        adapted = "\n".join(new for _, _, new in EXEC_CHANGES)
        for text in ("fs/androidDeliveryRead", "file.take(max_bytes + 1)",
                     "metadata.len() > max_bytes", "bytes.len() as u64 > max_bytes",
                     "follow_symlinks: false", "8 * 1024 * 1024"):
            self.assertIn(text, adapted)
        self.assertNotIn("fs.read_file(", PROCESSOR)

    def test_chunked_delivery_uses_distinct_rpc_and_validates_receipt(self):
        adapted = "\n".join(new for _, _, new in EXEC_CHANGES)
        for value in ("fs/androidDeliveryChunk", "read_delivery_chunk", "no_follow::open_file",
                      "Sha256::new()", "file.metadata()?", "DELIVERY_CHUNK_BYTES",
                      "reader: (&mut file).take(size + 1)", "sha2 0.10.9"):
            self.assertIn(value, adapted)
        for value in ("android_read_delivery_chunk", "response.offset != params.offset",
                      "response.version", "response.sha256", "chunk length mismatch"):
            self.assertIn(value, PROCESSOR)

    def test_multi_edit_source_is_composed_and_drift_does_not_write(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            sources = {}
            for relative, old, _ in EXEC_CHANGES:
                sources.setdefault(relative, []).append(old)
            for relative, anchors in sources.items():
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("\n".join(anchors))
            run(root, exec_only=True)
            first = {relative: (root / relative).read_text() for relative in sources}
            run(root, exec_only=True)
            self.assertEqual(first, {relative: (root / relative).read_text() for relative in sources})
            last = EXEC_CHANGES[-1][0]
            (root / last).write_text("drift")
            with self.assertRaises(ValueError):
                run(root, exec_only=True)
            self.assertEqual(first[EXEC_CHANGES[0][0]], (root / EXEC_CHANGES[0][0]).read_text())
