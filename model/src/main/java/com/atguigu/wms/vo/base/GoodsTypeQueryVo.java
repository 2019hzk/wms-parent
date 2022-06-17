package com.atguigu.wms.vo.base;

import lombok.Data;
import java.util.Date;
import io.swagger.annotations.ApiModelProperty;

@Data
public class GoodsTypeQueryVo {
	
	@ApiModelProperty(value = "名称")
	private String name;

	@ApiModelProperty(value = "parentId")
	private Long parentId;

	@ApiModelProperty(value = "状态")
	private Integer status;

	@ApiModelProperty(value = "创建人id")
	private Long createId;

	@ApiModelProperty(value = "创建人名称")
	private String createName;

	@ApiModelProperty(value = "最近更新人id")
	private Long updateId;

	@ApiModelProperty(value = "最近更新人名称")
	private String updateName;

	@ApiModelProperty(value = "创建时间")
	private Date createTime;

	@ApiModelProperty(value = "更新时间")
	private Date updateTime;

	@ApiModelProperty(value = "删除标记（0:不可用 1:可用）")
	private Integer isDeleted;


}

