#!/usr/bin/env python3
"""Review missing Maven Central metadata without mutating the committed trust store."""
import hashlib
import json
import pathlib
import subprocess
import tempfile
import xml.etree.ElementTree as ET

REPOSITORY = "https://repo.maven.apache.org/maven2/"
ARTIFACTS = (
    ("org.junit", "junit-bom", "5.10.2", "junit-bom-5.10.2.module"),
    ("org.junit", "junit-bom", "5.9.2", "junit-bom-5.9.2.module"),
    ("org.jetbrains.kotlinx", "kotlinx-coroutines-bom", "1.8.0", "kotlinx-coroutines-bom-1.8.0.pom"),
)
def fetch(url, output):
    subprocess.run(["curl", "--fail", "--silent", "--show-error", "--retry", "3", "--retry-all-errors",
                    "--proto", "=https", "--proto-redir", "=https", "--max-time", "60", url, "-o", str(output)], check=True)
def main():
    metadata = pathlib.Path(__file__).resolve().parents[2] / 'android-app/gradle/verification-metadata.xml'
    ns = {'v':'https://schema.gradle.org/dependency-verification'}
    root_xml = ET.parse(metadata).getroot()
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        for group, name, version, filename in ARTIFACTS:
            url = REPOSITORY + "/".join([group.replace(".", "/"), name, version, filename])
            artifact, checksum = root/filename, root/(filename+".sha1")
            fetch(url, artifact)
            fetch(url+".sha1", checksum)
            expected = checksum.read_text().strip().split()[0].lower()
            actual = hashlib.file_digest(artifact.open("rb"),"sha1").hexdigest()
            if expected != actual:
                raise ValueError("Maven Central published checksum mismatch: "+filename)
            digest = hashlib.file_digest(artifact.open("rb"),"sha256").hexdigest()
            component = next((c for c in root_xml.findall("v:components/v:component", ns) if (c.get("group"), c.get("name"), c.get("version")) == (group, name, version)), None)
            if component is None:
                raise ValueError("missing reviewed component: "+filename)
            matches = [s.get("value") for a in component.findall("v:artifact", ns) if a.get("name") == filename for s in a.findall("v:sha256", ns)]
            if matches != [digest]:
                raise ValueError("reviewed SHA-256 does not match official repository: "+filename)
            print(json.dumps({"group": group, "name": name, "version": version, "artifact": filename,
                              "sha1": actual, "sha256": digest, "source":url}), flush=True)
if __name__ == "__main__":
    main()
