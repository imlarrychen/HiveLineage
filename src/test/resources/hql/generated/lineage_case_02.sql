-- 覆盖点：动态分区开关 + insert overwrite + union all + distinct + 多来源表字段对齐
-- 预期：union all 两个分支的来源表都进入输入对象，目标字段对齐到 meta_mid_bdp_user_dashboard_pms_group。
set hive.exec.dynamic.partition = true;
set hive.exec.dynamic.partition.mode = nonstrict;
set hive.exec.parallel = true;
set hive.exec.parallel.thread.number = 3;

insert overwrite table etl_monitor.meta_mid_bdp_user_dashboard_pms_group
select distinct
       c.domain
      ,c.bbk_name
      ,c.dept_ful_nm
      ,c.user_id
      ,c.user_name
      ,c.emp_name
      ,c.role as user_role
      ,b.proj_id
      ,e.name as proj_name
      ,a.dsh_id
      ,b.name as dsh_name
      ,f.name as dsh_owner
      ,'按用户共享仪表盘' as dis_type
      ,current_timestamp() as dw_upd_tm
from etl_monitor.bdp_meta_mcmeta_online_share_dash a
inner join etl_monitor.meta_mid_bdp_usr_info c
on a.user_id = c.user_id
and c.user_name <> 'admin'
inner join etl_monitor.bdp_meta_mcmeta_online_dashboard b
on a.dsh_id = b.dsh_id
and b.is_del = 0
inner join etl_monitor.bdp_meta_mcmeta_online_project e
on b.proj_id = e.proj_id
inner join etl_monitor.bdp_meta_leviathan_t_account f
on b.user_id = f.user_id
where a.type = 0
union all
select distinct
       c.domain
      ,c.bbk_name
      ,c.dept_ful_nm
      ,c.user_id
      ,c.user_name
      ,c.emp_name
      ,c.role as user_role
      ,b.proj_id
      ,e.name as proj_name
      ,a.dsh_id
      ,b.name as dsh_name
      ,f.name as dsh_owner
      ,'按分组共享仪表盘' as dis_type
      ,current_timestamp() as dw_upd_tm
from etl_monitor.bdp_meta_mcmeta_online_share_dash a
inner join etl_monitor.meta_mid_bdp_grp_usr_map c
on a.user_id = c.group_id
inner join etl_monitor.bdp_meta_mcmeta_online_dashboard b
on a.dsh_id = b.dsh_id
and b.is_del = 0
inner join etl_monitor.bdp_meta_mcmeta_online_project e
on b.proj_id = e.proj_id
inner join etl_monitor.bdp_meta_leviathan_t_account f
on b.user_id = f.user_id
where a.type = 1;
