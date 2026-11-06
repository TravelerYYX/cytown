-- MySQL 8. Apply after P36, with the application stopped while wallet columns change.
ALTER TABLE app_user MODIFY coin_balance DECIMAL(20,1) NOT NULL DEFAULT 0;
ALTER TABLE user_coin_ledger
  MODIFY amount DECIMAL(20,1) NOT NULL,
  MODIFY balance_after DECIMAL(20,1) NOT NULL;
ALTER TABLE coin_punch_config MODIFY coins DECIMAL(20,1) NOT NULL;
ALTER TABLE user_coin_punch MODIFY coins DECIMAL(20,1) NOT NULL;

CREATE TABLE IF NOT EXISTS shop_product (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  sku VARCHAR(48) NOT NULL UNIQUE,
  version BIGINT NOT NULL DEFAULT 1,
  name VARCHAR(80) NOT NULL,
  description VARCHAR(500) NOT NULL,
  images_json JSON NOT NULL,
  frame_url VARCHAR(2048) NOT NULL,
  price_fen BIGINT NOT NULL,
  discounts_json JSON NOT NULL,
  sort_order INT NOT NULL,
  enabled BOOLEAN NOT NULL,
  sold_count BIGINT NOT NULL DEFAULT 0,
  INDEX idx_shop_catalog (enabled, sort_order, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS shop_order (
  id CHAR(33) NOT NULL PRIMARY KEY,
  user_id BIGINT NOT NULL,
  product_id BIGINT NOT NULL,
  request_key VARCHAR(80) NOT NULL,
  product_json JSON NOT NULL,
  method VARCHAR(10) NOT NULL,
  amount_fen BIGINT NOT NULL,
  amount_coins DECIMAL(20,1) NOT NULL,
  profile_id VARCHAR(40) NULL,
  channel VARCHAR(4) NULL,
  status VARCHAR(10) NOT NULL,
  trade_no VARCHAR(80) NULL UNIQUE,
  error_message VARCHAR(200) NOT NULL DEFAULT '',
  created_at DATETIME NOT NULL,
  expires_at DATETIME NOT NULL,
  next_check_at DATETIME NOT NULL,
  UNIQUE KEY uk_shop_request (user_id, request_key),
  INDEX idx_shop_pending (user_id, product_id, status),
  INDEX idx_shop_reconcile (status, next_check_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

START TRANSACTION;
INSERT INTO shop_product(sku,name,description,images_json,frame_url,price_fen,discounts_json,sort_order,enabled)
SELECT seed.sku,seed.name,seed.description,JSON_ARRAY(CONCAT('/uploads/shop/',seed.sku,'-cover.webp')),
       CONCAT('/uploads/shop/',seed.sku,'-frame.png'),seed.fen,
       JSON_OBJECT('go',80,'plus',70,'pro',70,'max',70,'ultra',70),seed.position,TRUE
FROM (
  SELECT 'moon-orbit' sku,'金月星轨' name,'把一弯金月和星轨戴在身边。' description,300 fen,1 position
  UNION ALL SELECT 'sakura-ribbon','樱绒缎带','樱色花瓣与缎带交叠的春日头像框。',300,2
  UNION ALL SELECT 'ice-aurora','冰晶极光','凝结在冰晶边缘的一缕极光。',300,3
  UNION ALL SELECT 'clover-dew','薄荷露光','四叶草和晨露为头像带来一点好运。',300,4
  UNION ALL SELECT 'coral-sunset','珊瑚落日','珊瑚橘云朵环绕的黄昏天际。',600,5
  UNION ALL SELECT 'pearl-tide','珍珠潮汐','贝壳、珍珠与气泡组成的海蓝头像框。',600,6
  UNION ALL SELECT 'amethyst-wings','紫晶蝶影','紫晶与蝶翼在头像边缘轻轻闪耀。',600,7
  UNION ALL SELECT 'maple-fox','枫火灵狐','灵狐双耳和红枫点亮秋日头像。',600,8
  UNION ALL SELECT 'nebula-signal','星云来讯','虹彩星云与卫星划过未来轨道。',900,9
  UNION ALL SELECT 'golden-phoenix','鎏金凤凰','飞扬的金色凤羽簇拥点点余烬。',900,10
) seed
ON DUPLICATE KEY UPDATE version=shop_product.version;

INSERT IGNORE INTO system_config(config_key,config_value,remark)
VALUES ('shop_h5_base_url','','商铺 H5 站点基础地址'),
       ('shop_mine_entry_initialized','0','商铺快捷入口初始化');
SELECT config_value INTO @shop_entry_seed FROM system_config WHERE config_key='shop_mine_entry_initialized' FOR UPDATE;
SELECT COALESCE(MAX(sort_order),0)+1 INTO @shop_entry_order FROM mine_entry;
INSERT INTO mine_entry(title,description,icon_url,link_url,target_type,open_mode,show_topbar,sort_order,enabled)
SELECT '商铺','选购个性装扮','/uploads/shop/mine-shop.png','/pages/shop/index','app','inapp',TRUE,@shop_entry_order,TRUE
WHERE @shop_entry_seed='0' AND NOT EXISTS (SELECT 1 FROM mine_entry WHERE link_url='/pages/shop/index' AND target_type='app');
UPDATE system_config SET config_value='1' WHERE config_key='shop_mine_entry_initialized';
COMMIT;
