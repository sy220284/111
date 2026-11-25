import tempfile
import unittest
from pathlib import Path
from PIL import Image, ImageDraw
from ui_visual_audit import signature,diff,compare

class VisualAuditTests(unittest.TestCase):
    def test_uniform_and_oversized_screens_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/"screen.png"
            metadata={"physical_size":"100x200","width_px":100,"height_px":200}
            Image.new("RGB",(100,200),"white").save(p)
            with self.assertRaises(ValueError): signature(p,metadata)
            Image.new("RGB",(101,200),"black").save(p)
            with self.assertRaises(ValueError): signature(p,metadata)
    def test_same_image_and_changed_content_are_distinguished(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/"screen.png"
            metadata={"physical_size":"100x200","width_px":100,"height_px":200}
            img=Image.new("RGB",(100,200),"white")
            draw=ImageDraw.Draw(img)
            draw.rectangle((5,5,90,90),fill="navy")
            draw.rectangle((5,110,90,140),fill="green")
            img.save(p)
            first=signature(p,metadata)
            self.assertFalse(diff(first,first)["review"])
            draw.rectangle((5,5,90,90),fill="orange")
            img.save(p)
            self.assertTrue(diff(first,signature(p,metadata))["review"])
    def test_missing_and_changed_configuration_never_claim_comparison(self):
        self.assertEqual("baseline_pending",compare(None,{})["status"])
        self.assertEqual("baseline_pending",compare({"modes":{}},{"modes":{"baseline":{"configuration":{}}}})["status"])
