import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("automatic", Path(__file__).with_name("automatic-server-backup.py"))
automatic = importlib.util.module_from_spec(spec)
spec.loader.exec_module(automatic)


class RetentionTest(unittest.TestCase):
    def test_keeps_two_and_waits_for_two_newer_remote_copies(self):
        for count in (0, 1, 2, 3, 30):
            names = [f"{number:03d}" for number in range(count)]
            entries = {name: {"uploaded": False} for name in names}
            local = set(names)
            self.assertEqual(automatic.local_expired(entries, local, set()), [])
            verified = set(automatic.newest(names))
            self.assertEqual(automatic.local_expired(entries, local, verified), names[:-2])

    def test_only_verified_managed_objects_can_be_pruned(self):
        entries = {"first": {"size": 12, "uploaded": True}, "pending": {"size": 12, "uploaded": False}}
        listing = [{"Name": name, "Size": 12} for name in ("first", "pending", "manual", "other-dataset")]
        self.assertEqual(automatic.verified_remote(entries, listing), {"first"})
        with self.assertRaises(ValueError):
            automatic.verified_remote(entries, listing + [listing[0]])
        listing[0]["Size"] = 1
        with self.assertRaises(ValueError):
            automatic.verified_remote(entries, listing)


if __name__ == "__main__":
    unittest.main()
