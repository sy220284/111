import unittest
from prepare_android_remote_delivery import CHANGES, adapt, PROCESSOR

class RemoteDeliveryAdapterTest(unittest.TestCase):
    def test_exact_and_idempotent(self):
        for _, old, new in CHANGES:
            self.assertEqual(adapt(old, old, new), new)
            self.assertEqual(adapt(new, old, new), new)
            with self.assertRaises(ValueError):
                adapt(old + old, old, new)
            with self.assertRaises(ValueError):
                adapt("changed upstream", old, new)

    def test_rejects_local_and_symlinks_and_large_files(self):
        for text in ("LOCAL_ENVIRONMENT_ID", "join_descendant", "root != params.workspace_uri",
                     "follow_symlinks: false", "metadata.is_symlink", "bytes.len() as u64 > MAX_BYTES"):
            self.assertIn(text, PROCESSOR)
