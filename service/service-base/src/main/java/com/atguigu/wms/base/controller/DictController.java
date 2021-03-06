package com.atguigu.wms.base.controller;

import com.atguigu.wms.common.result.Result;
import com.atguigu.wms.base.service.DictService;
import com.atguigu.wms.model.base.Dict;
import io.swagger.annotations.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import java.util.List;

/**
 * @author qy
 */
//@CrossOrigin //跨域
@Api(tags = "数据字典管理")
@RestController
@RequestMapping(value = "/admin/base/dict")
public class DictController   {

    @Autowired
    private DictService dictService;

    @ApiOperation(value = "根据id获取名称")
    @GetMapping("getNameById/{id}")
    public String importData(@PathVariable Long id) {
        return dictService.getNameById(id);
    }



//    @ApiOperation(value = "获取数据字典名称")
//    @GetMapping(value = "/getName/{parentDictCode}/{value}")
//    public String getName(
//            @ApiParam(name = "parentDictCode", value = "上级编码", required = true)
//            @PathVariable("parentDictCode") String parentDictCode,
//
//            @ApiParam(name = "value", value = "值", required = true)
//            @PathVariable("value") String value) {
//        return dictService.getNameByParentDictCodeAndValue(parentDictCode, value);
//    }

//    @ApiOperation(value = "获取数据字典名称")
//    @ApiImplicitParam(name = "value", value = "值", required = true, dataType = "Long", paramType = "path")
//    @GetMapping(value = "/getName/{value}")
//    public String getName(
//            @ApiParam(name = "value", value = "值", required = true)
//            @PathVariable("value") String value) {
//        return dictService.getNameByParentDictCodeAndValue("", value);
//    }
}

