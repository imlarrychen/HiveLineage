insert into dwd.t(name) select tag from ods.t lateral view explode(array('fixed')) generated as tag;
