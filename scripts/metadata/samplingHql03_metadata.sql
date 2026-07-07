-- Metadata for samplingHql03.txt.
-- Target/source tables:
--   mtr_cpr_100.SLC54_T_USEROPLOG_H
--   mtr_cpr_100.SLC54_T_USEROPLOG

start transaction;

delete from metadata_column_v2
where lower(db_en_name) = lower('mtr_cpr_100')
  and lower(tb_en_name) in (lower('SLC54_T_USEROPLOG_H'), lower('SLC54_T_USEROPLOG'));

set @base_id = (select coalesce(max(id), 0) from metadata_column_v2);

insert into metadata_column_v2
    (id, db_en_name, tb_en_name, column_name, column_type, integer_idx, is_active)
values
    (@base_id + 1,  'mtr_cpr_100', 'SLC54_T_USEROPLOG_H', 'WRKDAT',          'string', 1,  b'1'),
    (@base_id + 2,  'mtr_cpr_100', 'SLC54_T_USEROPLOG_H', 'SEQID',           'string', 2,  b'1'),
    (@base_id + 3,  'mtr_cpr_100', 'SLC54_T_USEROPLOG_H', 'SUBSEQ',          'string', 3,  b'1'),
    (@base_id + 4,  'mtr_cpr_100', 'SLC54_T_USEROPLOG_H', 'CZID',            'string', 4,  b'1'),
    (@base_id + 5,  'mtr_cpr_100', 'SLC54_T_USEROPLOG_H', 'CZNAME',          'string', 5,  b'1'),
    (@base_id + 6,  'mtr_cpr_100', 'SLC54_T_USEROPLOG_H', 'BUSTYP',          'string', 6,  b'1'),
    (@base_id + 7,  'mtr_cpr_100', 'SLC54_T_USEROPLOG_H', 'IDTYP',           'string', 7,  b'1'),
    (@base_id + 8,  'mtr_cpr_100', 'SLC54_T_USEROPLOG_H', 'OPTYP',           'string', 8,  b'1'),
    (@base_id + 9,  'mtr_cpr_100', 'SLC54_T_USEROPLOG_H', 'OPCOD',           'string', 9,  b'1'),
    (@base_id + 10, 'mtr_cpr_100', 'SLC54_T_USEROPLOG_H', 'CZRESULT',        'string', 10, b'1'),
    (@base_id + 11, 'mtr_cpr_100', 'SLC54_T_USEROPLOG_H', 'OPUSER',          'string', 11, b'1'),
    (@base_id + 12, 'mtr_cpr_100', 'SLC54_T_USEROPLOG_H', 'SBHCOD',          'string', 12, b'1'),
    (@base_id + 13, 'mtr_cpr_100', 'SLC54_T_USEROPLOG_H', 'OPTIME',          'string', 13, b'1'),
    (@base_id + 14, 'mtr_cpr_100', 'SLC54_T_USEROPLOG_H', 'PRCSTS',          'string', 14, b'1'),
    (@base_id + 15, 'mtr_cpr_100', 'SLC54_T_USEROPLOG_H', 'MSGID',           'string', 15, b'1'),
    (@base_id + 16, 'mtr_cpr_100', 'SLC54_T_USEROPLOG_H', 'NO_USEFUL_FILED', 'string', 16, b'1'),
    (@base_id + 17, 'mtr_cpr_100', 'SLC54_T_USEROPLOG_H', 'BIZ_DATE',        'string', 17, b'1'),
    (@base_id + 18, 'mtr_cpr_100', 'SLC54_T_USEROPLOG',   'WRKDAT',          'string', 1,  b'1'),
    (@base_id + 19, 'mtr_cpr_100', 'SLC54_T_USEROPLOG',   'SEQID',           'string', 2,  b'1'),
    (@base_id + 20, 'mtr_cpr_100', 'SLC54_T_USEROPLOG',   'SUBSEQ',          'string', 3,  b'1'),
    (@base_id + 21, 'mtr_cpr_100', 'SLC54_T_USEROPLOG',   'CZID',            'string', 4,  b'1'),
    (@base_id + 22, 'mtr_cpr_100', 'SLC54_T_USEROPLOG',   'CZNAME',          'string', 5,  b'1'),
    (@base_id + 23, 'mtr_cpr_100', 'SLC54_T_USEROPLOG',   'BUSTYP',          'string', 6,  b'1'),
    (@base_id + 24, 'mtr_cpr_100', 'SLC54_T_USEROPLOG',   'IDTYP',           'string', 7,  b'1'),
    (@base_id + 25, 'mtr_cpr_100', 'SLC54_T_USEROPLOG',   'OPTYP',           'string', 8,  b'1'),
    (@base_id + 26, 'mtr_cpr_100', 'SLC54_T_USEROPLOG',   'OPCOD',           'string', 9,  b'1'),
    (@base_id + 27, 'mtr_cpr_100', 'SLC54_T_USEROPLOG',   'CZRESULT',        'string', 10, b'1'),
    (@base_id + 28, 'mtr_cpr_100', 'SLC54_T_USEROPLOG',   'OPUSER',          'string', 11, b'1'),
    (@base_id + 29, 'mtr_cpr_100', 'SLC54_T_USEROPLOG',   'SBHCOD',          'string', 12, b'1'),
    (@base_id + 30, 'mtr_cpr_100', 'SLC54_T_USEROPLOG',   'OPTIME',          'string', 13, b'1'),
    (@base_id + 31, 'mtr_cpr_100', 'SLC54_T_USEROPLOG',   'PRCSTS',          'string', 14, b'1'),
    (@base_id + 32, 'mtr_cpr_100', 'SLC54_T_USEROPLOG',   'MSGID',           'string', 15, b'1'),
    (@base_id + 33, 'mtr_cpr_100', 'SLC54_T_USEROPLOG',   'BIZ_DATE',        'string', 16, b'1');

commit;
