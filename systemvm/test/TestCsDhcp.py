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

import unittest
import os
import tempfile
from pathlib import Path
import mock
from cs.CsDhcp import CsDhcp
from cs import CsHelper
import merge


class TestCsDhcp(unittest.TestCase):

    def setUp(self):
        merge.DataBag.DPATH = "."

    # @mock.patch('cs.CsDhcp.CsHelper')
    # @mock.patch('cs.CsDhcp.CsDnsMasq')
    def test_init(self):
        csdhcp = CsDhcp("dhcpentry", {})
        self.assertTrue(csdhcp is not None)

    def test_remove_expunged_lease_preserves_other_entries_and_file_permissions(self):
        with tempfile.TemporaryDirectory() as directory:
            leases = Path(directory) / "dnsmasq.leases"
            retained = "200 aa:bb:cc:dd:ee:02 10.1.1.20 other *\nmalformed\n"
            leases.write_text("100 aa:bb:cc:dd:ee:01 10.1.1.2 removed *\n" + retained)
            leases.chmod(0o640)
            original = leases.stat()
            with mock.patch('cs.CsDhcp.LEASES', str(leases)), mock.patch('cs.CsDhcp.CsHelper.service') as service:
                dhcp = CsDhcp("dhcpentry", {})
                self.assertTrue(dhcp.ensure_lease_removed("10.1.1.2"))
                self.assertEqual(retained, leases.read_text())
                self.assertEqual(original.st_mode, leases.stat().st_mode)
                self.assertEqual((original.st_uid, original.st_gid), (leases.stat().st_uid, leases.stat().st_gid))
                service.assert_called_once_with("dnsmasq", "reload")
                self.assertFalse(dhcp.ensure_lease_removed("10.1.1.2"))
                self.assertEqual(["dnsmasq.leases"], os.listdir(directory))

    def test_unknown_lease_does_not_replace_file_or_reload(self):
        with tempfile.TemporaryDirectory() as directory:
            leases = Path(directory) / "dnsmasq.leases"
            leases.write_text("100 aa:bb:cc:dd:ee:01 10.1.1.2 present *\n")
            original = leases.stat()
            with mock.patch('cs.CsDhcp.LEASES', str(leases)), mock.patch('cs.CsDhcp.CsHelper.service') as service:
                self.assertFalse(CsDhcp("dhcpentry", {}).remove_lease("10.1.1.3"))
                self.assertEqual(original.st_ino, leases.stat().st_ino)
                service.assert_not_called()
                self.assertEqual(["dnsmasq.leases"], os.listdir(directory))

    def test_missing_lease_file_is_safe(self):
        with tempfile.TemporaryDirectory() as directory:
            with mock.patch('cs.CsDhcp.LEASES', str(Path(directory) / "missing")):
                self.assertFalse(CsDhcp("dhcpentry", {}).ensure_lease_removed("10.1.1.2"))


if __name__ == '__main__':
    unittest.main()
