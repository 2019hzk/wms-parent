package com.atguigu.wms.outbound.service.impl;

import com.alibaba.fastjson.JSON;
import com.atguigu.wms.base.client.*;
import com.atguigu.wms.common.constant.MqConst;
import com.atguigu.wms.common.security.AuthContextHolder;
import com.atguigu.wms.common.service.RabbitService;
import com.atguigu.wms.common.util.NoUtils;
import com.atguigu.wms.enums.*;
import com.atguigu.wms.model.base.GoodsInfo;
import com.atguigu.wms.model.base.WareConfig;
import com.atguigu.wms.model.outbound.*;
import com.atguigu.wms.outbound.service.*;
import com.atguigu.wms.vo.outbound.OutPickingTaskQueryVo;
import com.atguigu.wms.outbound.mapper.OutPickingTaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.joda.time.DateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

@Service
@SuppressWarnings({"unchecked", "rawtypes"})
public class OutPickingTaskServiceImpl extends ServiceImpl<OutPickingTaskMapper, OutPickingTask> implements OutPickingTaskService {

	@Resource
	private OutPickingTaskMapper outPickingTaskMapper;

	@Resource
	private WareConfigFeignClient wareConfigFeignClient;

	@Resource
	private OutOrderItemService outOrderItemService;

	@Resource
	private OutOrderService outOrderService;

	@Resource
	private OutPickingItemService outPickingItemService;

	@Resource
	private GoodsInfoFeignClient goodsInfoFeignClient;

	@Resource
	private WarehouseInfoFeignClient warehouseInfoFeignClient;

	@Resource
	private StoreshelfInfoFeignClient storeshelfInfoFeignClient;

	@Resource
	private OutDeliverTaskService outDeliverTaskService;

	@Resource
	private RabbitService rabbitService;

	@Override
	public IPage<OutPickingTask> selectPage(Page<OutPickingTask> pageParam, OutPickingTaskQueryVo outPickingTaskQueryVo) {
		IPage<OutPickingTask> page = outPickingTaskMapper.selectPage(pageParam, outPickingTaskQueryVo);
		page.getRecords().forEach(item -> {
			item.setStatusName(item.getStatus().getComment());
			item.setWarehouseName(warehouseInfoFeignClient.getNameById(item.getWarehouseId()));
			item.setStoreshelfName(storeshelfInfoFeignClient.getNameById(item.getStoreshelfId()));
		});
		return page;
	}

	@Override
	public OutPickingTask getOutPickingTask(Long id) {
		OutPickingTask outPickingTask = this.getById(id);
		outPickingTask.setStatusName(outPickingTask.getStatus().getComment());
		outPickingTask.setWarehouseName(warehouseInfoFeignClient.getNameById(outPickingTask.getWarehouseId()));
		outPickingTask.setStoreshelfName(storeshelfInfoFeignClient.getNameById(outPickingTask.getStorehouseId()));

		List<OutPickingItem> outPickingItemList = outPickingItemService.findByOutPickingId(id);
		outPickingTask.setOutPickingItemList(outPickingItemList);
		return outPickingTask;
	}

	@Transactional(rollbackFor = {Exception.class})
    @Override
    public void run() {
		WareConfig waveConfig = wareConfigFeignClient.getWaveConfig();

		//????????????
		LambdaQueryWrapper<OutOrderItem> queryWrapper = new LambdaQueryWrapper<OutOrderItem>();

		//???????????????
		Date startTime = new DateTime().toDate();
		Date endTime = new DateTime(startTime).plusMinutes(waveConfig.getIntervalTime()).toDate();
		//queryWrapper.le(OutOrderItem::getCreateTime, endTime);
		queryWrapper.eq(OutOrderItem::getStatus, OutOrderItemStatus.PENDING_PICKING);
		List<OutOrderItem> outOrderItemList = outOrderItemService.list(queryWrapper);

		//?????????????????????
		Set<Long> outOrderIdSet = outOrderItemList.stream().map(OutOrderItem::getOutOrderId).collect(Collectors.toSet());
		Iterator<Long> outOrderIdIterator = outOrderIdSet.iterator();
		while (outOrderIdIterator.hasNext()) {
			Long outOrderId = outOrderIdIterator.next();
			outOrderService.updateStatus(outOrderId, OutOrderStatus.PICKING_RUN);
		}

		//??????????????????????????????
		Map<Long, List<OutOrderItem>> dimensionIdToOutOrderItemList = null;
		//????????????
		if(waveConfig.getDimension() == WareConfigDimension.STORESHELF) {
			dimensionIdToOutOrderItemList = outOrderItemList.stream().collect(Collectors.groupingBy(OutOrderItem::getStoreshelfId));
		} else if(waveConfig.getDimension() == WareConfigDimension.STOREHOUSE) {
			dimensionIdToOutOrderItemList = outOrderItemList.stream().collect(Collectors.groupingBy(OutOrderItem::getStorehouseId));
		} else {
			dimensionIdToOutOrderItemList = outOrderItemList.stream().collect(Collectors.groupingBy(OutOrderItem::getGoodsId));
		}

		Iterator<Map.Entry<Long, List<OutOrderItem>>> iterator = dimensionIdToOutOrderItemList.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<Long, List<OutOrderItem>> entry = iterator.next();
			List<OutOrderItem> orderItemList = entry.getValue();

			//??????????????????
			OutOrderItem outOrderItem = orderItemList.get(0);
			OutPickingTask outPickingTask = new OutPickingTask();
			outPickingTask.setTaskNo(NoUtils.getOrderNo());
			outPickingTask.setWarehouseId(outOrderItem.getWarehouseId());
			outPickingTask.setStoreareaId(outOrderItem.getStoreareaId());
			outPickingTask.setStoreshelfId(outOrderItem.getStoreshelfId());
			outPickingTask.setStorehouseId(outOrderItem.getStorehouseId());
			Integer totalPickingCount = orderItemList.stream().map(OutOrderItem::getBuyCount).reduce((a, b) -> a + b).get();
			outPickingTask.setPickingCount(totalPickingCount);
			outPickingTask.setStatus(OutPickingStatus.PENDING_PICKING);
			this.save(outPickingTask);

			//??????
			List<OutOrderItem> outOrderItemUpdateList = orderItemList.stream().map(item -> {
				OutOrderItem orderItem = new OutOrderItem();
				orderItem.setId(item.getId());
				orderItem.setStatus(OutOrderItemStatus.ASSIGN);
				orderItem.setPickingTaskId(outPickingTask.getId());
				return orderItem;
			}).collect(Collectors.toList());
			outOrderItemService.updateBatchById(outOrderItemUpdateList);

			//?????????????????????
			List<OutPickingItem> outPickingItemList = new ArrayList<>();
			Iterator<Map.Entry<Long, List<OutOrderItem>>> goodsIterator = orderItemList.stream().collect(Collectors.groupingBy(OutOrderItem::getGoodsId)).entrySet().iterator();
			while (goodsIterator.hasNext()) {
				Map.Entry<Long, List<OutOrderItem>> goodsEntry = goodsIterator.next();
				List<OutOrderItem> outOrderItems = goodsEntry.getValue();
				OutOrderItem item = orderItemList.get(0);
				Integer pickingCount = outOrderItems.stream().map(OutOrderItem::getBuyCount).reduce((a, b) -> a + b).get();

				OutPickingItem outPickingItem = new OutPickingItem();
				outPickingItem.setOutPickingId(outPickingTask.getId());

				GoodsInfo goodsInfo = goodsInfoFeignClient.getGoodsInfo(item.getGoodsId());

				outPickingItem.setGoodsId(goodsInfo.getId());
				outPickingItem.setGoodsName(goodsInfo.getName());
				outPickingItem.setBarcode(goodsInfo.getBarcode());
				outPickingItem.setPickingCount(pickingCount);
				outPickingItem.setWeight(goodsInfo.getWeight());
				outPickingItem.setVolume(goodsInfo.getVolume());
				outPickingItem.setWarehouseId(item.getWarehouseId());
				outPickingItem.setStoreareaId(item.getStoreareaId());
				outPickingItem.setStoreshelfId(item.getStoreshelfId());
				outPickingItem.setStorehouseId(item.getStorehouseId());
				outPickingItem.setStatus(OutPickingStatus.PENDING_PICKING);

				outPickingItemList.add(outPickingItem);
			}
			outPickingItemService.saveBatch(outPickingItemList);
		}
	}

	@Transactional(rollbackFor = {Exception.class})
	@Override
	public void finish(Long id) {
		OutPickingTask outPickingTask = this.getById(id);
		outPickingTask.setStatus(OutPickingStatus.FINISH);
		outPickingTask.setPickingTime(new Date());
		outPickingTask.setPickingUserId(AuthContextHolder.getUserId());
		outPickingTask.setPickingUser(AuthContextHolder.getUserName());
		this.updateById(outPickingTask);

		//????????????
		List<OutPickingItem> outPickingItemList = outPickingItemService.findByOutPickingId(id);
		List<OutPickingItem> outPickingItemUpdateList = outPickingItemList.stream().map(item -> {
			OutPickingItem outPickingItem = new OutPickingItem();
			outPickingItem.setId(item.getId());
			outPickingItem.setStatus(OutPickingStatus.FINISH);
			return outPickingItem;
		}).collect(Collectors.toList());
		outPickingItemService.updateBatchById(outPickingItemUpdateList);

		//????????????????????????????????????
		outOrderItemService.updateBatchStatusByPickingTaskId(id, OutOrderItemStatus.PICKING);

		//????????????????????????????????????????????????
		//???????????????????????????
		List<OutOrderItem> outOrderItemPickingList = outOrderItemService.list(new LambdaQueryWrapper<OutOrderItem>().eq(OutOrderItem::getStatus, OutOrderItemStatus.PICKING));
		//?????????????????????????????????????????????id
		List<Long> outOrderIdList = outOrderItemPickingList.stream().map(OutOrderItem::getOutOrderId).collect(Collectors.toList());
		//???????????????id????????????????????????????????????????????????????????????????????????
		List<OutOrderItem> allOutOrderItemList = outOrderItemService.list(new LambdaQueryWrapper<OutOrderItem>().in(OutOrderItem::getOutOrderId, outOrderIdList));
		//????????????id??????????????????????????????????????????????????????????????????????????????????????????
		Map<Long, List<OutOrderItem>> outOrderIdToOutOrderItemListMap = allOutOrderItemList.stream().collect(Collectors.groupingBy(OutOrderItem::getOutOrderId));
		Iterator<Map.Entry<Long, List<OutOrderItem>>> iterator = outOrderIdToOutOrderItemListMap.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<Long, List<OutOrderItem>> goodsEntry = iterator.next();
			Long outOrderId = goodsEntry.getKey();
			List<OutOrderItem> outOrderItems = goodsEntry.getValue();
			//???????????????????????????????????????????????????????????????????????????
			List<OutOrderItem> list = outOrderItems.stream().filter(item -> item.getStatus() != OutOrderItemStatus.PICKING).collect(Collectors.toList());
			if(list.size() == 0) {
				OutDeliverTask outDeliverTask = new OutDeliverTask();
				OutOrder outOrder = outOrderService.getById(outOrderId);
				outDeliverTask.setTaskNo(NoUtils.getOrderNo());
				outDeliverTask.setOutOrderId(outOrderId);
				outDeliverTask.setOutOrderNo(outOrder.getOutOrderNo());
				outDeliverTask.setWarehouseId(outOrder.getWarehouseId());
				outDeliverTask.setOrderComment(outOrder.getOrderComment());
				outDeliverTask.setConsignee(outOrder.getConsignee());
				outDeliverTask.setConsigneeTel(outOrder.getConsigneeTel());
				outDeliverTask.setDeliveryAddress(outOrder.getDeliveryAddress());
				int deliverCount = outOrderItems.stream().map(OutOrderItem::getBuyCount).reduce((a, b) -> a + b).get();
				outDeliverTask.setDeliverCount(deliverCount);
				outDeliverTask.setStatus(OutDeliverTaskStatus.PENDING_DELIVER);
				outDeliverTaskService.save(outDeliverTask);

				//?????????????????????
				outOrderService.updateStatus(outOrderId, OutOrderStatus.PENDING_DELIVER);

				//???????????????????????????????????????
				outOrderItemService.updateBatchStatusByOutOrderId(outOrderId, OutOrderItemStatus.FINISH);
			}
		}

		//????????????????????????
		rabbitService.sendMessage(MqConst.EXCHANGE_INVENTORY, MqConst.ROUTING_PICKING, JSON.toJSONString(outPickingItemList));
	}

	@Override
	public List<OutPickingTask> findByOutOrderId(Long outOrderId) {
		List<OutOrderItem> outOrderItemList = outOrderItemService.findByOutOrderId(outOrderId);
		Set<Long> pickingTaskIdSet = outOrderItemList.stream().filter(item -> null != item.getPickingTaskId()).map(OutOrderItem::getPickingTaskId).collect(Collectors.toSet());
		if(!CollectionUtils.isEmpty(pickingTaskIdSet)) {
			List<OutPickingTask> outPickingTaskList = this.listByIds(pickingTaskIdSet);
			outPickingTaskList.forEach(item -> {
				item.setStatusName(item.getStatus().getComment());
				item.setWarehouseName(warehouseInfoFeignClient.getNameById(item.getWarehouseId()));
				item.setStoreshelfName(storeshelfInfoFeignClient.getNameById(item.getStoreshelfId()));
			});
			return outPickingTaskList;
		}
		return Collections.emptyList();
	}

}
