# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from xml.etree import ElementTree


GENERATOR = Path(__file__).with_name("gen_toc.py")


class ApiDocumentationCategoriesTest(unittest.TestCase):
    def generate(self, root, commands):
        apis = root / "apis"
        apis.mkdir()
        files = []
        for command in commands:
            relative = "apis/" + command + ".xml"
            (root / relative).write_text(
                "<command><name>" + command + "</name>"
                "<isAsync>false</isAsync><isDeprecated>false</isDeprecated></command>",
                encoding="utf-8",
            )
            files.append(relative)
        return subprocess.run(
            [sys.executable, str(GENERATOR), *files], cwd=root,
            capture_output=True, text=True, check=False,
        )

    def test_bucket_and_object_storage_commands_generate_indexes(self):
        commands = [
            "createBucket", "updateBucket", "deleteBucket", "listBuckets", "syncBucketUsage",
            "getBucketCredentials", "rotateBucketCredentials", "getBucketObjectMetadata",
            "getBucketProtection", "reconcileBucketProtection", "getBucketDeletionImpact",
            "listBucketObjectVersions", "manageBucketObjectVersion", "purgeBucket", "getBucketPurgeStatus",
            "addObjectStoragePool", "listObjectStoragePools", "updateObjectStoragePool",
            "deleteObjectStoragePool", "checkObjectStoragePool",
        ]
        backup_commands = ["listBackups", "restoreBackup", "getVeeamRuntimeConfig", "reportBackupArtifactRestoreValidation"]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            result = self.generate(root, commands + backup_commands)
            self.assertEqual(0, result.returncode, result.stderr)
            toc = ElementTree.fromstring(
                '<root xmlns:xsl="http://www.w3.org/1999/XSL/Transform">'
                + (root / "generatetoc_include.xsl").read_text() + "</root>"
            )
            categories = {}
            for box in toc.findall(".//div[@class='apismallbullet_box']"):
                for link in box.findall(".//a"):
                    categories[Path(link.attrib["href"]).stem] = box.findtext("h5")
            for command in commands:
                self.assertEqual("Object Store", categories[command], command)
            for command in backup_commands:
                self.assertEqual("Backup and Recovery", categories[command], command)
            java = (root / "XmlToHtmlConverterData.java").read_text()
            for command in commands + backup_commands:
                self.assertIn('allCommandNames.add("' + command + '");', java)

    def test_unknown_command_still_requires_an_explicit_category(self):
        with tempfile.TemporaryDirectory() as directory:
            result = self.generate(Path(directory), ["unclassifiedCommand"])
            self.assertNotEqual(0, result.returncode)
            self.assertIn("Need to add a category for unclassifiedCommand.xml", result.stderr)


if __name__ == "__main__":
    unittest.main()
