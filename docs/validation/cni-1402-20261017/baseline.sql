SELECT 'UD',uuid,name,account_id,domain_id,for_cks,COALESCE(params,''),SHA2(user_data,256) FROM user_data WHERE removed IS NULL ORDER BY id;
SELECT 'K8S',uuid,name,state,account_id,domain_id,COALESCE(cni_config_id,''),COALESCE(cni_config_details,'') FROM kubernetes_cluster WHERE removed IS NULL ORDER BY id;
SELECT 'ANN',uuid,entity_uuid,entity_type,user_uuid,admins_only,SHA2(annotation,256) FROM annotations WHERE removed IS NULL ORDER BY id;
SELECT 'ACCOUNT',uuid,account_name,type,domain_id,state FROM account WHERE removed IS NULL ORDER BY id;
SELECT 'PROJECT',uuid,name,project_account_id,domain_id,state FROM projects WHERE removed IS NULL ORDER BY id;
SELECT 'DOMAIN',uuid,name,parent,path,level FROM domain WHERE removed IS NULL ORDER BY id;
