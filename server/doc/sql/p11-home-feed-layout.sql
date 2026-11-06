-- 可重复执行；保留管理员已经选择的默认布局。
INSERT IGNORE INTO system_config(config_key,config_value,remark)
VALUES ('home_default_feed_layout','single','首页默认帖子布局：single 单列，waterfall 双列瀑布');
