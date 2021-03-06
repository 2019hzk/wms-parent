package com.atguigu.wms.inventory.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.atguigu.wms.base.client.GoodsInfoFeignClient;
import com.atguigu.wms.base.client.StorehouseInfoFeignClient;
import com.atguigu.wms.base.client.WareConfigFeignClient;
import com.atguigu.wms.base.client.WarehouseInfoFeignClient;
import com.atguigu.wms.common.constant.MqConst;
import com.atguigu.wms.common.constant.RedisConst;
import com.atguigu.wms.common.execption.WmsException;
import com.atguigu.wms.common.result.Result;
import com.atguigu.wms.common.result.ResultCodeEnum;
import com.atguigu.wms.common.security.AuthContextHolder;
import com.atguigu.wms.common.service.RabbitService;
import com.atguigu.wms.enums.InvLogType;
import com.atguigu.wms.enums.OutOrderStatus;
import com.atguigu.wms.inventory.service.InvBusinessRepeatService;
import com.atguigu.wms.inventory.service.InvLogService;
import com.atguigu.wms.model.base.GoodsInfo;
import com.atguigu.wms.model.base.WareConfig;
import com.atguigu.wms.model.inventory.InventoryInfo;
import com.atguigu.wms.model.outbound.OutOrder;
import com.atguigu.wms.model.outbound.OutPickingItem;
import com.atguigu.wms.outbound.client.OutboundFeignClient;
import com.atguigu.wms.vo.PageVo;
import com.atguigu.wms.vo.base.GoodsInfoQueryVo;
import com.atguigu.wms.vo.inventory.InventoryFormVo;
import com.atguigu.wms.vo.outbound.OrderItemLockVo;
import com.atguigu.wms.inventory.mapper.InventoryInfoMapper;
import com.atguigu.wms.inventory.service.InventoryInfoService;
import com.atguigu.wms.vo.inventory.InventoryInfoVo;
import com.atguigu.wms.vo.outbound.OrderLockVo;
import com.atguigu.wms.vo.outbound.OrderResponseVo;
import com.atguigu.wms.vo.outbound.OutOrderAddressVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@SuppressWarnings({"unchecked", "rawtypes"})
public class InventoryInfoServiceImpl extends ServiceImpl<InventoryInfoMapper, InventoryInfo> implements InventoryInfoService {

	@Resource
	private InventoryInfoMapper inventoryInfoMapper;

	@Resource
	private InvLogService invLogService;

	@Resource
	private InvBusinessRepeatService invBusinessRepeatService;

	@Resource
	private WarehouseInfoFeignClient warehouseInfoFeignClient;

	@Resource
	private StorehouseInfoFeignClient storehouseInfoFeignClient;

	@Resource
	private GoodsInfoFeignClient goodsInfoFeignClient;

	@Resource
	private OutboundFeignClient outboundFeignClient;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Resource
	private RabbitService rabbitService;

	@Resource
	private WareConfigFeignClient wareConfigFeignClient;

	@Override
	public PageVo<GoodsInfo> selectPage(Page<GoodsInfo> pageParam, GoodsInfoQueryVo goodsInfoQueryVo) {
		PageVo<GoodsInfo> pageVo = goodsInfoFeignClient.findPage(pageParam.getCurrent(), pageParam.getSize(), goodsInfoQueryVo);
		List<GoodsInfo> goodsInfoList = pageVo.getRecords();
		if(CollectionUtils.isEmpty(goodsInfoList)) return pageVo;

		List<Long> goodsIdList = goodsInfoList.stream().map(GoodsInfo::getId).collect(Collectors.toList());

		LambdaQueryWrapper<InventoryInfo> queryWrapper = new LambdaQueryWrapper<InventoryInfo>();
		queryWrapper.in(InventoryInfo::getGoodsId, goodsIdList);
		if(null != AuthContextHolder.getWarehouseId()) {
			queryWrapper.eq(InventoryInfo::getWarehouseId, AuthContextHolder.getWarehouseId());
		}
		List<InventoryInfo> inventoryInfoList = this.list(queryWrapper);
		inventoryInfoList.forEach(item -> {
			item.setWarehouseName(warehouseInfoFeignClient.getNameById(item.getWarehouseId()));
			item.setStorehouseName(storehouseInfoFeignClient.getNameById(item.getStorehouseId()));
		});
		Map<Long, List<InventoryInfo>> goodsIdToInventoryInfoListMap = inventoryInfoList.stream().collect(Collectors.groupingBy(InventoryInfo::getGoodsId));
		for(GoodsInfo goodsInfo : goodsInfoList) {
			List<InventoryInfo> inventoryInfos = goodsIdToInventoryInfoListMap.get(goodsInfo.getId());
			if(CollectionUtils.isEmpty(inventoryInfos)) inventoryInfos = new ArrayList<>();
			goodsInfo.setInventoryInfoList(inventoryInfos);
		}
		return pageVo;
	}

	@Transactional(rollbackFor = {Exception.class})
    @Override
    public Boolean syncInventory(String inOrderNo, List<InventoryInfoVo> inventoryInfoVoList, InvLogType invLogType) {
		for(InventoryInfoVo inventoryInfoVo : inventoryInfoVoList) {
			InventoryInfo inventoryInfo = this.inventoryInfoMapper.getCheck(inventoryInfoVo.getWarehouseId(), inventoryInfoVo.getGoodsId());
			if(null == inventoryInfo) {
				inventoryInfo = new InventoryInfo();
				BeanUtils.copyProperties(inventoryInfoVo, inventoryInfo);
				inventoryInfo.setTotalCount(inventoryInfoVo.getPutawayCount());
				inventoryInfo.setAvailableCount(inventoryInfoVo.getPutawayCount());
				inventoryInfo.setLockCount(0);
				inventoryInfo.setWarningCount(10);
				this.save(inventoryInfo);
			} else {
				inventoryInfoMapper.putaway(inventoryInfoVo.getWarehouseId(), inventoryInfoVo.getGoodsId(), inventoryInfoVo.getPutawayCount());
			}

			//????????????
			invLogService.log(inventoryInfo.getWarehouseId(), inventoryInfo.getGoodsId(), invLogType, inventoryInfoVo.getPutawayCount(), "?????????????????????" + inOrderNo);
		}
        return true;
    }

	@Transactional(rollbackFor = {Exception.class})
	@Override
	public Boolean countingSyncInventory(String taskNo, List<InventoryInfoVo> inventoryInfoVoList, InvLogType invLogType) {
		for(InventoryInfoVo inventoryInfoVo : inventoryInfoVoList) {
			InventoryInfo inventoryInfo = this.inventoryInfoMapper.getCheck(inventoryInfoVo.getWarehouseId(), inventoryInfoVo.getGoodsId());
			if(null != inventoryInfo) {
				inventoryInfoMapper.putaway(inventoryInfoVo.getWarehouseId(), inventoryInfoVo.getGoodsId(), inventoryInfoVo.getPutawayCount());
				//????????????
				invLogService.log(inventoryInfo.getWarehouseId(), inventoryInfo.getGoodsId(), invLogType, inventoryInfoVo.getPutawayCount(), "???????????????????????????" + taskNo);
			} else {
				throw new WmsException(ResultCodeEnum.DATA_ERROR);
			}
		}
		return true;
	}

	@Override
	public InventoryInfo getByGoodsIdAndWarehouseId(Long goodsId, Long warehouseId) {
		return this.getOne(new LambdaQueryWrapper<InventoryInfo>().eq(InventoryInfo::getGoodsId, goodsId).eq(InventoryInfo::getWarehouseId,warehouseId));
	}

	@Override
	public List<InventoryInfo> findByStorehouseId(Long storehouseId) {
		List<InventoryInfo> list = this.list(new LambdaQueryWrapper<InventoryInfo>().eq(InventoryInfo::getStorehouseId, storehouseId));
		list.forEach(item -> {
			GoodsInfo goodsInfo = goodsInfoFeignClient.getGoodsInfo(item.getGoodsId());
			item.setGoodsInfo(goodsInfo);
			item.setWarehouseName(warehouseInfoFeignClient.getNameById(item.getWarehouseId()));
			item.setStorehouseName(storehouseInfoFeignClient.getNameById(item.getStorehouseId()));
		});
		return list;
	}

	/**
	 *
	 * @param orderLockVo
	 * @return ????????????????????????id?????????????????????id??????
	 */
	public Result<List<OrderResponseVo>> checkAndLock(OrderLockVo orderLockVo) {
		String key = "checkAndLock:"+orderLockVo.getOrderNo();
		String keyList = "checkAndLockList:"+orderLockVo.getOrderNo();
		//??????????????????
		boolean isExist = redisTemplate.opsForValue().setIfAbsent(key, orderLockVo.getOrderNo(), 1, TimeUnit.HOURS);
		if (!isExist) {
			String data = redisTemplate.opsForValue().get(keyList);
			if(!StringUtils.isEmpty(data)) {
				//??????????????????
				List<OrderResponseVo> orderResponseVoList = JSONArray.parseArray(data, OrderResponseVo.class);
				return Result.ok(orderResponseVoList);
			} else {
				//????????????????????????????????????
				return Result.build(null, ResultCodeEnum.LOCK_REPEAT);
			}
		}
		//?????????????????????
		List<OrderItemLockVo> orderItemLockVoList = orderLockVo.getOrderItemVoList();
		//????????????????????????????????????
		//??????id???????????????????????????id??????
		Map<Long, List<Long>> goodsIdToAvailableWarehouseIdListMap = new HashMap<>();
		//???????????????????????????????????????
		List<Long> allWarehouseIdList = new ArrayList<>();
		Map<Long, Long> goodsIdToSkuIdMap = new HashMap<>();
		for(OrderItemLockVo orderItemLockVo : orderItemLockVoList) {
			//??????skuid????????????id
			GoodsInfo goodsInfo = goodsInfoFeignClient.getGoodsInfoBySkuId(orderItemLockVo.getSkuId());
			if(null == goodsInfo) {
				redisTemplate.delete(key);
				return Result.build(null, ResultCodeEnum.INVENTORY_LESS);
			}
			orderItemLockVo.setGoodsId(goodsInfo.getId());
			goodsIdToSkuIdMap.put(goodsInfo.getId(), goodsInfo.getSkuId());

			// ????????????????????????????????????????????????????????????
			List<InventoryInfo> availableInventoryInfoList = inventoryInfoMapper.check(orderItemLockVo.getGoodsId(), orderItemLockVo.getCount());
			if(CollectionUtils.isEmpty(availableInventoryInfoList)) {
				//????????????
				redisTemplate.delete(key);
				return Result.build(null, ResultCodeEnum.INVENTORY_LESS);
			}
			List<Long> warehouseIdList = availableInventoryInfoList.stream().map(InventoryInfo::getWarehouseId).collect(Collectors.toList());
			goodsIdToAvailableWarehouseIdListMap.put(orderItemLockVo.getGoodsId(), warehouseIdList);
			allWarehouseIdList.addAll(warehouseIdList);
		}

		//??????,???????????????????????????
		allWarehouseIdList = allWarehouseIdList.stream().distinct().collect(Collectors.toList());
		//?????????????????????????????????????????????????????????
		OutOrderAddressVo outOrderAddressVo = new OutOrderAddressVo();
		BeanUtils.copyProperties(orderLockVo, outOrderAddressVo);
		outOrderAddressVo.setWarehouseIdList(allWarehouseIdList);
		//?????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
		List<Long> priorityWarehouseIdList = warehouseInfoFeignClient.findPriorityWarehouseIdList(outOrderAddressVo);
		if(CollectionUtils.isEmpty(priorityWarehouseIdList)) {
			//????????????
			redisTemplate.delete(key);
			return Result.build(null, ResultCodeEnum.INVENTORY_LESS);
		}

		//??????????????????????????????????????????
		List<OrderResponseVo> orderResponseVoList = new ArrayList<>();
		//???????????????????????????????????????????????????
		List<Long> assignGoodsIdList = new ArrayList<>();
		for(Long warehouseId : priorityWarehouseIdList) {
			List<Long> goodsIdList = new ArrayList<>();
			List<Long> skuIdList = new ArrayList<>();
			Iterator<Map.Entry<Long, List<Long>>> iterator = goodsIdToAvailableWarehouseIdListMap.entrySet().iterator();
			while (iterator.hasNext()) {
				Map.Entry<Long, List<Long>> entry = iterator.next();
				Long goodsId = entry.getKey();
				if(!assignGoodsIdList.contains(goodsId)) {
					List<Long> warehouseIdList = entry.getValue();
					if(warehouseIdList.contains(warehouseId)) {
						goodsIdList.add(goodsId);
						skuIdList.add(goodsIdToSkuIdMap.get(goodsId));
						assignGoodsIdList.add(goodsId);
					}
				}
			}
			if(goodsIdList.size() > 0) {
				OrderResponseVo orderResponseVo = new OrderResponseVo();
				orderResponseVo.setWarehouseId(warehouseId);
				orderResponseVo.setSkuIdList(skuIdList);
				orderResponseVo.setGoodsIdList(goodsIdList);
				orderResponseVoList.add(orderResponseVo);
			}
		}
		//??????????????????????????????????????????????????????????????????????????????????????????????????????orderResponseVoList
		// ??????????????????
		for(OrderResponseVo orderResponseVo: orderResponseVoList) {
			Long warehouseId = orderResponseVo.getWarehouseId();
			List<Long> goodsIdList = orderResponseVo.getGoodsIdList();
			List<OrderItemLockVo> currentGoodsLockVoList = orderItemLockVoList.stream().filter(orderItemLockVo -> goodsIdList.contains(orderItemLockVo.getGoodsId())).collect(Collectors.toList());
			// ??????????????????
			this.lock(orderLockVo.getOrderNo(), warehouseId, currentGoodsLockVoList);
		}
		// ??????????????????????????????????????????????????????????????????????????????
		if (orderItemLockVoList.stream().anyMatch(orderItemLockVo -> !orderItemLockVo.getLock())) {
			// ??????????????????????????????????????????????????????
			orderItemLockVoList.stream().filter(OrderItemLockVo::getLock).forEach(itemLockVo -> {
				this.inventoryInfoMapper.unLock(itemLockVo.getWarehouseId(), itemLockVo.getGoodsId(), itemLockVo.getCount());

				//????????????
				invLogService.log(itemLockVo.getWarehouseId(), itemLockVo.getGoodsId(), InvLogType.OUT_UNLOCK, itemLockVo.getCount(), "??????????????????"+orderLockVo.getOrderNo());
			});
			//throw new WmsException(ResultCodeEnum.INVENTORY_LESS);
			return Result.build(null, ResultCodeEnum.INVENTORY_LESS);
		}

		// ???????????????????????????????????????????????????????????????????????????redis?????????????????????????????? ?????? ?????????
		// ???outOrderNo??????key??????goodsLockVoList??????????????????value
		this.redisTemplate.opsForValue().set(RedisConst.INVENTORY_INFO_PREFIX + orderLockVo.getOrderNo(), JSON.toJSONString(orderItemLockVoList));

		//????????????goodsId??????
		orderResponseVoList.forEach(item -> item.setGoodsIdList(null));
		redisTemplate.opsForValue().set(keyList, JSON.toJSONString(orderResponseVoList), 1, TimeUnit.HOURS);
		return Result.ok(orderResponseVoList);
	}

	/**
	 * ????????????????????????
	 * @param warehouseId
	 * @param orderItemLockVoList
	 * @return
	 */
	private Boolean lock(String orderNo, Long warehouseId, List<OrderItemLockVo> orderItemLockVoList) {
		orderItemLockVoList.forEach(orderItemLockVo -> {
			orderItemLockVo.setWarehouseId(warehouseId);
			int lock = inventoryInfoMapper.lock(warehouseId, orderItemLockVo.getGoodsId(), orderItemLockVo.getCount());
			if(lock == 1) {
				orderItemLockVo.setLock(true);

				//????????????
				invLogService.log(warehouseId, orderItemLockVo.getGoodsId(), InvLogType.OUT_LOCK, orderItemLockVo.getCount(), "??????????????????"+orderNo);
			} else {
				orderItemLockVo.setLock(false);
			}
		});
		return true;
	}

	@Transactional(rollbackFor = {Exception.class})
	@Override
	public void unlock(String orderNo) {
		//?????????????????????????????????
		Boolean isRepeat = invBusinessRepeatService.isRepeat("unlock:" + orderNo, "????????????");
		if(isRepeat) return;

		// ?????????????????????????????????
		String orderItemLockVoListJson = (String)this.redisTemplate.opsForValue().get(RedisConst.INVENTORY_INFO_PREFIX + orderNo);
		if (StringUtils.isEmpty(orderItemLockVoListJson)){
			return ;
		}

		// ????????????
		List<OrderItemLockVo> orderItemLockVoList = JSON.parseArray(orderItemLockVoListJson, OrderItemLockVo.class);
		orderItemLockVoList.forEach(lockVo -> {
            int unLock = this.inventoryInfoMapper.unLock(lockVo.getWarehouseId(), lockVo.getGoodsId(), lockVo.getCount());
            if(unLock == 0) {
                throw new WmsException(ResultCodeEnum.DATA_ERROR);
            }

			//????????????
			invLogService.log(lockVo.getWarehouseId(), lockVo.getGoodsId(), InvLogType.OUT_UNLOCK, lockVo.getCount(), "??????????????????"+orderNo);
		});

		// ??????????????????????????????????????????????????????????????????????????????
		this.redisTemplate.delete(RedisConst.INVENTORY_INFO_PREFIX + orderNo);
	}

	@Transactional(rollbackFor = {Exception.class})
	@Override
	public void minus(String outOrderNo) {
		//?????????????????????????????????
		Boolean isRepeat = invBusinessRepeatService.isRepeat("minus:" + outOrderNo, "?????????????????????");
		if(isRepeat) return;

		// ?????????????????????
		OutOrder outOrder = outboundFeignClient.getByOutOrderNo(outOrderNo);
		if (null == outOrder || CollectionUtils.isEmpty(outOrder.getOutOrderItemList())){
			return;
		}

		//????????????
		if (outOrder.getStatus() == OutOrderStatus.FINISH){
			return;
		}

		// ?????????
		outOrder.getOutOrderItemList().forEach(item -> {
			int minus = this.inventoryInfoMapper.minus(item.getWarehouseId(), item.getGoodsId(), item.getBuyCount());
            if(minus == 0) {
                throw new WmsException(ResultCodeEnum.DATA_ERROR);
            }

			//????????????
			invLogService.log(item.getWarehouseId(), item.getGoodsId(), InvLogType.OUT_MINUS, item.getBuyCount(), "??????????????????????????????"+outOrder.getOutOrderNo());
		});

        //??????????????????
        Boolean isUpdate = outboundFeignClient.updateFinishStatus(outOrder.getId());
        if(!isUpdate) {
            throw new WmsException(ResultCodeEnum.DATA_ERROR);
        }

		//?????????????????????????????????????????????
		rabbitService.sendMessage(MqConst.EXCHANGE_INVENTORY, MqConst.ROUTING_DELIVER, outOrder.getOrderNo());
	}

	@Override
	public Integer checkInventory(Long skuId) {
		GoodsInfo goodsInfo = goodsInfoFeignClient.getGoodsInfoBySkuId(skuId);
		if(null == goodsInfo) return 0;
		int count = this.count(new LambdaQueryWrapper<InventoryInfo>().eq(InventoryInfo::getGoodsId, goodsInfo.getId()).gt(InventoryInfo::getAvailableCount, 0));
		return count > 0 ? 1 : 0;
	}

	@Override
	public void updateInventory(InventoryFormVo inventoryFormVo) {
		InventoryInfo inventoryInfo = this.inventoryInfoMapper.getCheck(inventoryFormVo.getWarehouseId(), inventoryFormVo.getGoodsId());
		if(null != inventoryInfo) {
			inventoryInfoMapper.putaway(inventoryFormVo.getWarehouseId(), inventoryFormVo.getGoodsId(), inventoryFormVo.getCount());
			//????????????
			invLogService.log(inventoryInfo.getWarehouseId(), inventoryInfo.getGoodsId(), InvLogType.HAND_PUTAWAY, inventoryFormVo.getCount(), "????????????" + AuthContextHolder.getUserName());
		} else {
			throw new WmsException(ResultCodeEnum.DATA_ERROR);
		}
	}

	@Transactional(rollbackFor = {Exception.class})
    @Override
    public void picking(List<OutPickingItem> outPickingItemList) {
        outPickingItemList.forEach(item -> {
			inventoryInfoMapper.updatePicking(item.getWarehouseId(), item.getGoodsId(), item.getPickingCount());
		});
    }
}
