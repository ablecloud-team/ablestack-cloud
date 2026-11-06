# 수정 전 실제 DAO 재현

```text
-------------------------------------------------------------------------------
Test set: org.apache.cloudstack.storage.dataservice.StorageDirectoryPolicyCoordinatorDaoTest
-------------------------------------------------------------------------------
Tests run: 1, Failures: 0, Errors: 1, Skipped: 0, Time elapsed: 10.148 s <<< FAILURE! - in org.apache.cloudstack.storage.dataservice.StorageDirectoryPolicyCoordinatorDaoTest
sourceOnlyVerifiedWithoutPathReceiptCommitsRealBoundShareAndDaoReload(org.apache.cloudstack.storage.dataservice.StorageDirectoryPolicyCoordinatorDaoTest)  Time elapsed: 10.08 s  <<< ERROR!
com.cloud.exception.InvalidParameterValueException: DIRECTORY_BINDING_RECEIPT_MISSING
	at org.apache.cloudstack.storage.dataservice.StorageDirectoryPolicyCoordinatorDaoTest.sourcePolicyRoundtrip(StorageDirectoryPolicyCoordinatorDaoTest.java:373)
	at org.apache.cloudstack.storage.dataservice.StorageDirectoryPolicyCoordinatorDaoTest.sourceOnlyVerifiedWithoutPathReceiptCommitsRealBoundShareAndDaoReload(StorageDirectoryPolicyCoordinatorDaoTest.java:389)


```
