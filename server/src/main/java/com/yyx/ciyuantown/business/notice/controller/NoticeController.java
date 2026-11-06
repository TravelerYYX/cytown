package com.yyx.ciyuantown.business.notice.controller;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yyx.ciyuantown.business.homeconfig.dto.IdParam;
import com.yyx.ciyuantown.business.notice.dto.*;
import com.yyx.ciyuantown.business.notice.entity.NoticeCategory;
import com.yyx.ciyuantown.business.notice.service.NoticeService;
import com.yyx.ciyuantown.business.notice.vo.*;
import com.yyx.ciyuantown.common.api.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController @RequestMapping("/notice") @RequiredArgsConstructor
public class NoticeController {
 private final NoticeService service;
 @GetMapping("/category/list")public Result<List<NoticeCategory>>categories(){return Result.success(service.categories(false));}
 @PostMapping("/category/create")public Result<NoticeCategory>createCategory(@RequestBody NoticeCategoryParam p){return Result.success(service.saveCategory(p,true));}
 @PostMapping("/category/update")public Result<NoticeCategory>updateCategory(@RequestBody NoticeCategoryParam p){return Result.success(service.saveCategory(p,false));}
 @PostMapping("/category/delete")public Result<Void>deleteCategory(@RequestBody IdParam p){service.deleteCategory(p.id());return Result.success();}
 @GetMapping("/list")public Result<Page<NoticeListVO>>list(@ModelAttribute NoticeQueryParam p){return Result.success(service.list(p,false));}
 @GetMapping("/{id}")public Result<NoticeDetailVO>detail(@PathVariable Long id){return Result.success(service.detail(id,false));}
 @PostMapping("/create")public Result<Long>create(@RequestBody NoticeParam p){return Result.success(service.save(p,true));}
 @PostMapping("/update")public Result<Long>update(@RequestBody NoticeParam p){return Result.success(service.save(p,false));}
 @PostMapping("/delete")public Result<Void>delete(@RequestBody IdParam p){service.delete(p.id());return Result.success();}
 @PostMapping("/publish")public Result<Void>publish(@RequestBody IdParam p){service.publish(p.id());return Result.success();}
 @PostMapping("/unpublish")public Result<Void>unpublish(@RequestBody IdParam p){service.unpublish(p.id());return Result.success();}
}
