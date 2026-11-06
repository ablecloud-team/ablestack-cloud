# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See NOTICE. Apache License, Version 2.0.
# http://www.apache.org/licenses/LICENSE-2.0
import copy
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/"debian/usr/local/lib/ablestack-storage"))
import shared_directory_policy as h

class Publication(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name)
        self.sources=[(self.root/"one.conf",123),(self.root/"two.conf",456)]
        self.sections={str(p):set() for p,_ in self.sources};self.defaults={str(p):{} for p,_ in self.sources}
        self.row={"uuid":"resource","name":"waiting","state":"Ready","acls":[],"config":{"guestOk":False,"pathBindingVersion":1,
            "volumeMountPath":"/srv/ablestack-storage/volumes/volume","relativeSharePath":"projects/team",
            "backingPath":"/srv/ablestack-storage/volumes/volume/projects/team","pathIdentity":{"filesystemUuid":"fs","volumeRootInode":128,"inode":42}}}
        self.desired=self.root/"desired.json";self.save()
    def tearDown(self):self.tmp.cleanup()
    def save(self,row=None):
        self.desired.write_text(json.dumps({"enabled":True,"shares":[row or self.row]}));self.desired.chmod(0o600)
    def command(self,args,**kw):
        source=args[-1]
        if len(args)==3:return ("[global]\n"+"\n".join("["+n+"]"for n in self.sections[source])).encode()
        parameter=next(v.split("=",1)[1]for v in args if v.startswith("--parameter-name="))
        return self.defaults[source].get(parameter,"no" if parameter=="inherit owner" else "").encode()
    def state(self,row=None):
        with patch.object(h,"smb_owned_sources",return_value=self.sources),patch.object(h,"checked",side_effect=self.command):
            return h.smb_publication_state(row or self.row,self.desired)
    def test_only_proven_pre_acl_state_can_have_no_sections(self):
        before=copy.deepcopy(self.row);result=self.state()
        self.assertEqual("UNPUBLISHED_WAITING_ACL",result["state"]);self.assertEqual(2,result["listeners"])
        self.assertEqual(before,self.row)
        for actual in result["runtime"]:h.inheritance_conflict(actual,"2775")
    def test_any_unexpected_publication_is_not_treated_as_waiting(self):
        self.sections[str(self.sources[0][0])].add("waiting")
        with self.assertRaisesRegex(h.PolicyError,"SMB_PUBLICATION_STATE_MISMATCH"):self.state()
    def test_published_share_requires_section_on_both_listeners(self):
        self.row["acls"]=[{"state":"Ready","principal":"user"}];self.save()
        for names in self.sections.values():names.add("waiting")
        self.assertEqual("PUBLISHED",self.state()["state"])
        self.sections[str(self.sources[1][0])].clear()
        with self.assertRaisesRegex(h.PolicyError,"SMB_PUBLISHED_SECTION_MISSING"):self.state()
    def test_guest_and_legacy_shares_cannot_use_the_missing_section_exception(self):
        for change in [{"guestOk":True},{"pathBindingVersion":None}]:
            row=copy.deepcopy(self.row);row["config"].update(change)
            with self.assertRaisesRegex(h.PolicyError,"SMB_PUBLISHED_SECTION_MISSING"):self.state(row)
    def test_stale_desired_identity_or_acl_cannot_admit_unpublished_fallback(self):
        for key,value in [("backingPath","/other"),("pathIdentity",{"inode":999})]:
            row=copy.deepcopy(self.row);row["config"][key]=value
            with self.assertRaisesRegex(h.PolicyError,"SMB_PUBLICATION_OBSERVATION_UNAVAILABLE"):self.state(row)
        saved=copy.deepcopy(self.row);saved["acls"]=[{"state":"Ready","principal":"user"}];self.save(saved)
        with self.assertRaisesRegex(h.PolicyError,"SMB_PUBLICATION_OBSERVATION_UNAVAILABLE"):self.state()
    def test_unpublished_still_checks_global_force_user_and_group(self):
        for key in ["force user","force group"]:
            self.defaults[str(self.sources[0][0])]={key:"forced"}
            result=self.state()
            with self.assertRaisesRegex(h.PolicyError,"SMB_PARENT_IDENTITY_CONFLICT"):
                for actual in result["runtime"]:h.inheritance_conflict(actual,"2775")
    def test_no_process_or_invalid_configuration_still_fails_closed(self):
        with self.assertRaisesRegex(h.PolicyError,"SMB_INHERITANCE_OBSERVATION_UNAVAILABLE"):h.smb_owned_sources([])
        with patch.object(h,"smb_owned_sources",return_value=self.sources),patch.object(h,"checked",side_effect=h.PolicyError("DIRECTORY_RUNTIME_COMMAND_FAILED")):
            with self.assertRaisesRegex(h.PolicyError,"DIRECTORY_RUNTIME_COMMAND_FAILED"):h.smb_publication_state(self.row,self.desired)
if __name__=="__main__":unittest.main()
