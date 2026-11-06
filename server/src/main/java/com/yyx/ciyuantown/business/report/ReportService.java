package com.yyx.ciyuantown.business.report;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyx.ciyuantown.common.exception.BizException;
import com.yyx.ciyuantown.common.storage.ResourceCleanupService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service @RequiredArgsConstructor
public class ReportService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final ResourceCleanupService cleanup;
    public record Option(String id,String kind,String name,int sortOrder) {}
    public record Options(List<Option> types,List<Option> reasons) {}
    public record OptionParam(Long id,String kind,String name,Integer sortOrder) {}
    public record Submit(String targetKind,String targetId,String typeId,String reasonId,String content,List<String> assetIds) {}
    public record Report(String id,String reporterId,String reporterName,String reportedUserId,String reportedUserName,
                         String targetKind,String targetId,String typeName,String reasonName,String content,String createTime) {}
    public record Detail(Report report,Object targetSnapshot,List<String> images) {}
    private static long id(String value) {
        try{long id=Long.parseLong(value);if(id>0)return id;}catch(Exception ignored){}
        throw new BizException("无效的举报标识");
    }
    private static long number(Object value){return ((Number)value).longValue();}
    private static void kind(String value){if(!Set.of("type","reason").contains(Objects.toString(value,"")))throw new BizException("无效的选项分类");}
    public List<Option> options(String kind) {
        kind(kind);
        return db.query("SELECT * FROM report_option WHERE kind=? AND deleted=FALSE ORDER BY sort_order,id",
            (r,i)->new Option(r.getString("id"),r.getString("kind"),r.getString("name"),r.getInt("sort_order")),kind);
    }
    public Options options(){return new Options(options("type"),options("reason"));}
    @Transactional public Option saveOption(OptionParam p,boolean create) {
        if(p==null)throw new BizException("请输入举报选项");kind(p.kind());
        String name=Objects.toString(p.name(),"").trim();
        if(name.isEmpty()||name.codePointCount(0,name.length())>100)throw new BizException("选项名称须为1至100字");
        int sort=p.sortOrder()==null?0:p.sortOrder();long id;
        if(create)id=new SimpleJdbcInsert(db).withTableName("report_option").usingColumns("kind","name","sort_order").usingGeneratedKeyColumns("id")
            .executeAndReturnKey(Map.of("kind",p.kind(),"name",name,"sort_order",sort)).longValue();
        else {
            if(p.id()==null||db.update("UPDATE report_option SET name=?,sort_order=? WHERE id=? AND kind=? AND deleted=FALSE",name,sort,p.id(),p.kind())!=1)
                throw new BizException("举报选项不存在");id=p.id();
        }
        return new Option(""+id,p.kind(),name,sort);
    }
    @Transactional public void deleteOption(Long id) {
        if(id==null||db.update("UPDATE report_option SET deleted=TRUE WHERE id=? AND deleted=FALSE",id)!=1)throw new BizException("举报选项不存在");
    }
    private String option(long id,String kind) {
        var rows=db.queryForList("SELECT name FROM report_option WHERE id=? AND kind=? AND deleted=FALSE FOR UPDATE",id,kind);
        if(rows.isEmpty())throw new BizException("举报选项已变更，请重新选择");return rows.getFirst().get("name").toString();
    }
    private Map<String,Object> user(long id) {
        var rows=db.queryForList("SELECT id,nickname,avatar,signature FROM app_user WHERE id=? AND status=0 AND (deleted=FALSE OR deleted IS NULL)",id);
        if(rows.isEmpty())throw new BizException("用户不存在或已注销");return rows.getFirst();
    }
    @Transactional public String submit(long reporter,Submit p) {
        if(p==null||!Set.of("user","post").contains(Objects.toString(p.targetKind(),"")))throw new BizException("无效的举报对象");
        String content=Objects.toString(p.content(),"").trim();
        if(content.isEmpty()||content.codePointCount(0,content.length())>200)throw new BizException("请输入1至200字举报内容");
        var ids=p.assetIds()==null?List.<String>of():p.assetIds();
        if(ids.size()>9)throw new BizException("最多上传9张证据图片");
        var assetIds=ids.stream().map(ReportService::id).toList();
        if(new HashSet<>(assetIds).size()!=assetIds.size())throw new BizException("证据图片不能重复");
        // Same resource lock as upload/delete: validated assets cannot disappear before attachment.
        cleanup.lock();
        long target=id(p.targetId()),type=id(p.typeId()),reason=id(p.reasonId()),reported=target;
        var snapshot=new LinkedHashMap<String,Object>();
        if("post".equals(p.targetKind())) {
            var posts=db.queryForList("SELECT id,author_id,title,content FROM post WHERE id=? AND status=1 FOR UPDATE",target);
            if(posts.isEmpty()||posts.getFirst().get("author_id")==null)throw new BizException("帖子不存在或已下架");
            var post=posts.getFirst();reported=number(post.get("author_id"));
            // Retain textual evidence; do not keep references to the author's media resources.
            snapshot.put("postId",""+target);snapshot.put("title",post.get("title"));snapshot.put("content",post.get("content"));
        }
        if(reporter==reported)throw new BizException("不能举报自己或自己的帖子");
        var author=user(reported);var viewer=user(reporter);
        snapshot.put("avatarUrl",author.get("avatar"));snapshot.put("userId",""+reported);snapshot.put("nickname",author.get("nickname"));snapshot.put("signature",author.get("signature"));
        String typeName=option(type,"type"),reasonName=option(reason,"reason");
        var images=new ArrayList<Map<String,Object>>();
        for(long asset:assetIds) {
            var rows=db.queryForList("SELECT id,url FROM upload_asset WHERE id=? AND owner_id=? AND media_type='image' AND status='ready' AND attached=FALSE FOR UPDATE",asset,reporter);
            if(rows.isEmpty())throw new BizException("证据图片无效或上传未完成，请重新上传");images.add(rows.getFirst());
        }
        var values=new LinkedHashMap<String,Object>();
        values.put("reporter_id",reporter);values.put("reported_user_id",reported);values.put("target_kind",p.targetKind());values.put("target_id",target);
        values.put("type_id",type);values.put("reason_id",reason);values.put("type_name",typeName);values.put("reason_name",reasonName);values.put("content",content);
        values.put("reporter_name",viewer.get("nickname"));values.put("reported_user_name",author.get("nickname"));
        try{values.put("target_snapshot",json.writeValueAsString(snapshot));}catch(JsonProcessingException e){throw new BizException("保存举报失败");}
        long report=new SimpleJdbcInsert(db).withTableName("user_report").usingColumns(values.keySet().toArray(String[]::new)).usingGeneratedKeyColumns("id").executeAndReturnKey(values).longValue();
        for(int index=0;index<images.size();index++) {
            var image=images.get(index);db.update("INSERT INTO user_report_image(report_id,asset_id,url,sort_order) VALUES(?,?,?,?)",report,image.get("id"),image.get("url"),index);
            db.update("UPDATE upload_asset SET attached=TRUE WHERE id=?",image.get("id"));
        }
        return ""+report;
    }
    private Report row(java.sql.ResultSet r,int i)throws java.sql.SQLException {
        return new Report(r.getString("id"),r.getString("reporter_id"),r.getString("reporter_name"),r.getString("reported_user_id"),r.getString("reported_user_name"),
            r.getString("target_kind"),r.getString("target_id"),r.getString("type_name"),r.getString("reason_name"),r.getString("content"),r.getTimestamp("create_time").toLocalDateTime().toString().replace('T',' '));
    }
    public Page<Report> list(int pageNum,int pageSize) {
        if(pageNum<1||pageSize<1||pageSize>100)throw new BizException("分页参数无效");
        long total=db.queryForObject("SELECT COUNT(*) FROM user_report",Long.class);
        return new Page<Report>(pageNum,pageSize,total).setRecords(db.query("SELECT * FROM user_report ORDER BY create_time DESC,id DESC LIMIT ? OFFSET ?",this::row,pageSize,((long)pageNum-1)*pageSize));
    }
    public Detail detail(long id) {
        var reports=db.query("SELECT * FROM user_report WHERE id=?",this::row,id);
        if(reports.isEmpty())throw new BizException("举报记录不存在");
        try{return new Detail(reports.getFirst(),json.readValue(db.queryForObject("SELECT target_snapshot FROM user_report WHERE id=?",String.class,id),Object.class),
            db.queryForList("SELECT url FROM user_report_image WHERE report_id=? ORDER BY sort_order,id",String.class,id));}
        catch(JsonProcessingException e){throw new BizException("举报详情读取失败");}
    }
}
