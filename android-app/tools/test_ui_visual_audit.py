import tempfile
import unittest
from pathlib import Path
from PIL import Image, ImageDraw
from ui_visual_audit import signature,diff,compare,write_gallery

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

    def test_gallery_preserves_old_new_and_difference(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)
            metadata={"physical_size":"100x200","width_px":100,"height_px":200}
            p=root/"s.png"
            img=Image.new("RGB",(100,200),"white")
            draw=ImageDraw.Draw(img)
            draw.rectangle((5,5,90,90),fill="navy")
            draw.rectangle((5,110,90,140),fill="green")
            img.save(p)
            a=signature(p,metadata)
            draw.rectangle((5,5,90,90),fill="orange")
            img.save(p)
            b=signature(p,metadata)
            old={"commit":"a","modes":{"baseline":{"configuration":metadata,"build_fingerprint":"same","screens":{"sample":a}}}}
            new={"commit":"b","modes":{"baseline":{"configuration":metadata,"build_fingerprint":"same","screens":{"sample":b}}}}
            report=compare(old,new)
            self.assertEqual("compared",report["status"])
            write_gallery(old,new,report,root)
            self.assertTrue((root/"index.html").is_file())
            self.assertEqual(3,len(list((root/"review").glob("*.png"))))

    def test_two_color_layout_is_valid_while_near_uniform_is_not(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/"screen.png"
            metadata={"physical_size":"128x256","width_px":128,"height_px":256}
            img=Image.new("RGB",(128,256),"white")
            ImageDraw.Draw(img).rectangle((0,0,63,255),fill="black")
            img.save(p)
            self.assertGreaterEqual(signature(p,metadata)["color_count"],2)
            img=Image.new("RGB",(128,256),(100,100,100))
            ImageDraw.Draw(img).rectangle((0,0,63,255),fill=(101,101,101))
            img.save(p)
            with self.assertRaises(ValueError): signature(p,metadata)
