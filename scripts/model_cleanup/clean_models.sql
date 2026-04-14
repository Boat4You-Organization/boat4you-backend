select m.id, m.name, m.manufacturer_id, mf.name as manufacturer, m.external_category_id, c.name as cat_name
from model m
         left join category c
                   on m.external_category_id = c.external_id
         left join manufacturer mf
                   on m.manufacturer_id = mf.id
where m.name in (select name
                 from model
                 group by name
                 having count(*) > 1)

-- clean models
update external_mapping
set system_id = 614
where system_id = 615
  and type = 'Model';

update yacht
set model_id = 614
where model_id = 615;

delete
from model
where id = 615;

select *
from external_mapping
where system_id = 424
  and type = 'Model';