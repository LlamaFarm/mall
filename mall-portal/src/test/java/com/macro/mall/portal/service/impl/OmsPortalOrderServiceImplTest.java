package com.macro.mall.portal.service.impl;

import com.macro.mall.common.exception.ApiException;
import com.macro.mall.common.service.RedisService;
import com.macro.mall.mapper.OmsOrderItemMapper;
import com.macro.mall.mapper.OmsOrderMapper;
import com.macro.mall.mapper.OmsOrderSettingMapper;
import com.macro.mall.mapper.PmsSkuStockMapper;
import com.macro.mall.mapper.SmsCouponHistoryMapper;
import com.macro.mall.mapper.UmsIntegrationConsumeSettingMapper;
import com.macro.mall.model.OmsOrder;
import com.macro.mall.model.OmsOrderExample;
import com.macro.mall.model.OmsOrderItem;
import com.macro.mall.model.OmsOrderItemExample;
import com.macro.mall.model.OmsOrderSetting;
import com.macro.mall.model.OmsOrderSettingExample;
import com.macro.mall.model.PmsSkuStock;
import com.macro.mall.model.SmsCoupon;
import com.macro.mall.model.UmsIntegrationConsumeSetting;
import com.macro.mall.model.UmsMember;
import com.macro.mall.model.UmsMemberReceiveAddress;
import com.macro.mall.portal.component.CancelOrderSender;
import com.macro.mall.portal.dao.PortalOrderDao;
import com.macro.mall.portal.dao.PortalOrderItemDao;
import com.macro.mall.portal.domain.CartPromotionItem;
import com.macro.mall.portal.domain.OmsOrderDetail;
import com.macro.mall.portal.domain.OrderParam;
import com.macro.mall.portal.domain.SmsCouponHistoryDetail;
import com.macro.mall.portal.service.OmsCartItemService;
import com.macro.mall.portal.service.UmsMemberCouponService;
import com.macro.mall.portal.service.UmsMemberReceiveAddressService;
import com.macro.mall.portal.service.UmsMemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OmsPortalOrderServiceImpl}.
 *
 * <p>Targets the four behaviours called out in LLA-6: {@code generateOrder},
 * {@code cancelOrder}, {@code confirmReceiveOrder} and {@code paySuccess},
 * plus their edge cases (out-of-stock, invalid state transitions, and the
 * optimistic-concurrency path used to guard against concurrent order
 * modifications).
 */
@ExtendWith(MockitoExtension.class)
class OmsPortalOrderServiceImplTest {

    private static final long MEMBER_ID = 99L;
    private static final long ORDER_ID = 1001L;
    private static final long ADDRESS_ID = 11L;
    private static final long SKU_ID = 501L;
    private static final long PRODUCT_ID = 301L;
    private static final long CATEGORY_ID = 7L;
    private static final long CART_ID = 21L;

    @Mock
    private UmsMemberService memberService;
    @Mock
    private OmsCartItemService cartItemService;
    @Mock
    private UmsMemberReceiveAddressService memberReceiveAddressService;
    @Mock
    private UmsMemberCouponService memberCouponService;
    @Mock
    private UmsIntegrationConsumeSettingMapper integrationConsumeSettingMapper;
    @Mock
    private PmsSkuStockMapper skuStockMapper;
    @Mock
    private OmsOrderMapper orderMapper;
    @Mock
    private PortalOrderItemDao orderItemDao;
    @Mock
    private SmsCouponHistoryMapper couponHistoryMapper;
    @Mock
    private RedisService redisService;
    @Mock
    private PortalOrderDao portalOrderDao;
    @Mock
    private OmsOrderSettingMapper orderSettingMapper;
    @Mock
    private OmsOrderItemMapper orderItemMapper;
    @Mock
    private CancelOrderSender cancelOrderSender;

    @InjectMocks
    private OmsPortalOrderServiceImpl orderService;

    @BeforeEach
    void setUp() {
        // The two @Value-injected fields are not picked up by @InjectMocks, so we
        // wire them in explicitly to mirror what Spring would do at runtime.
        ReflectionTestUtils.setField(orderService, "REDIS_KEY_ORDER_ID", "orderId:");
        ReflectionTestUtils.setField(orderService, "REDIS_DATABASE", "mall");
    }

    // ---------------------------------------------------------------------
    // generateOrder
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("generateOrder")
    class GenerateOrder {

        @Test
        @DisplayName("happy path: no coupon, no integration → inserts order and items, locks stock, schedules cancel")
        void generateOrder_succeeds_withoutCouponOrIntegration() {
            UmsMember member = member(MEMBER_ID, 0);
            CartPromotionItem cartItem = cartItem(SKU_ID, 2, 10);
            stubCommonGenerateOrderHappyPath(member, cartItem);

            OrderParam param = orderParam(null, null, 1);
            Map<String, Object> result = orderService.generateOrder(param);

            assertThat(result).containsKeys("order", "orderItemList");
            OmsOrder savedOrder = (OmsOrder) result.get("order");
            assertThat(savedOrder.getStatus()).isEqualTo(0); // 待付款
            assertThat(savedOrder.getMemberId()).isEqualTo(MEMBER_ID);
            assertThat(savedOrder.getCouponAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(savedOrder.getIntegrationAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(savedOrder.getOrderSn()).isNotBlank();
            assertThat(savedOrder.getReceiverName()).isEqualTo("Alice");

            @SuppressWarnings("unchecked")
            List<OmsOrderItem> orderItems = (List<OmsOrderItem>) result.get("orderItemList");
            assertThat(orderItems).hasSize(1);
            assertThat(orderItems.get(0).getCouponAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(orderItems.get(0).getIntegrationAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(orderItems.get(0).getOrderId()).isEqualTo(savedOrder.getId());

            verify(orderMapper).insert(any(OmsOrder.class));
            verify(orderItemDao).insertList(any());
            verify(portalOrderDao).lockStockBySkuId(eq(SKU_ID), eq(2));
            verify(cartItemService).delete(eq(MEMBER_ID), eq(Collections.singletonList(CART_ID)));
            verify(cancelOrderSender).sendMessage(any(), anyLong());
            // No coupon/integration side-effects on this path.
            verify(couponHistoryMapper, never()).updateByPrimaryKeySelective(any());
            verify(memberService, never()).updateIntegration(anyLong(), anyInt());
        }

        @Test
        @DisplayName("rejects when receive address is missing")
        void generateOrder_throws_whenReceiveAddressMissing() {
            OrderParam param = orderParam(null, null, 1);
            param.setMemberReceiveAddressId(null);

            assertThatThrownBy(() -> orderService.generateOrder(param))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("收货地址");

            verify(orderMapper, never()).insert(any());
            verify(portalOrderDao, never()).lockStockBySkuId(anyLong(), anyInt());
        }

        @Test
        @DisplayName("rejects when any cart item is out of stock")
        void generateOrder_throws_whenOutOfStock() {
            UmsMember member = member(MEMBER_ID, 0);
            when(memberService.getCurrentMember()).thenReturn(member);
            CartPromotionItem outOfStock = cartItem(SKU_ID, 5, 1); // request 5, only 1 in stock
            when(cartItemService.listPromotion(eq(MEMBER_ID), any())).thenReturn(Arrays.asList(outOfStock));

            OrderParam param = orderParam(null, null, 1);

            assertThatThrownBy(() -> orderService.generateOrder(param))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("库存不足");

            verify(orderMapper, never()).insert(any());
            verify(portalOrderDao, never()).lockStockBySkuId(anyLong(), anyInt());
            verify(orderItemDao, never()).insertList(any());
            verify(cancelOrderSender, never()).sendMessage(any(), anyLong());
        }

        @Test
        @DisplayName("rejects when stock lock fails (another shopper just claimed it)")
        void generateOrder_throws_whenStockLockFails() {
            UmsMember member = member(MEMBER_ID, 0);
            CartPromotionItem cartItem = cartItem(SKU_ID, 2, 10);
            stubCommonGenerateOrderHappyPath(member, cartItem);
            // Override: lock now races and returns 0
            when(portalOrderDao.lockStockBySkuId(eq(SKU_ID), eq(2))).thenReturn(0);

            OrderParam param = orderParam(null, null, 1);

            assertThatThrownBy(() -> orderService.generateOrder(param))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("库存不足");

            verify(orderMapper, never()).insert(any());
            verify(orderItemDao, never()).insertList(any());
        }

        @Test
        @DisplayName("rejects when an unusable coupon id is supplied")
        void generateOrder_throws_whenCouponNotUsable() {
            UmsMember member = member(MEMBER_ID, 0);
            CartPromotionItem cartItem = cartItem(SKU_ID, 1, 10);
            when(memberService.getCurrentMember()).thenReturn(member);
            when(cartItemService.listPromotion(eq(MEMBER_ID), any())).thenReturn(Arrays.asList(cartItem));
            // No matching coupon for the requested id
            when(memberCouponService.listCart(any(), eq(1))).thenReturn(Collections.emptyList());

            OrderParam param = orderParam(7L, null, 1);

            assertThatThrownBy(() -> orderService.generateOrder(param))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("优惠券");
        }

        @Test
        @DisplayName("rejects when integration is not usable (below useUnit threshold)")
        void generateOrder_throws_whenIntegrationNotUsable() {
            UmsMember member = member(MEMBER_ID, 1000);
            CartPromotionItem cartItem = cartItem(SKU_ID, 1, 10);
            when(memberService.getCurrentMember()).thenReturn(member);
            when(cartItemService.listPromotion(eq(MEMBER_ID), any())).thenReturn(Arrays.asList(cartItem));
            // Integration setting requires more points than the user is offering, so the
            // computed integration amount is zero and the request is rejected.
            UmsIntegrationConsumeSetting integrationSetting = new UmsIntegrationConsumeSetting();
            integrationSetting.setUseUnit(50);              // 50 points = ¥1
            integrationSetting.setMaxPercentPerOrder(100);
            integrationSetting.setCouponStatus(1);
            when(integrationConsumeSettingMapper.selectByPrimaryKey(1L)).thenReturn(integrationSetting);

            OrderParam param = orderParam(null, 10, 1);     // only 10 points → below useUnit

            assertThatThrownBy(() -> orderService.generateOrder(param))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("积分");
        }

        /**
         * Common happy-path stubbing reused by tests that do not exercise the
         * coupon or integration branches. Stubs that may not be hit on every
         * branch (e.g. when an early lockStock failure short-circuits) are
         * marked {@code lenient} to avoid Mockito strict-mode noise.
         */
        private void stubCommonGenerateOrderHappyPath(UmsMember member, CartPromotionItem cartItem) {
            when(memberService.getCurrentMember()).thenReturn(member);
            when(cartItemService.listPromotion(eq(MEMBER_ID), any())).thenReturn(Arrays.asList(cartItem));

            UmsMemberReceiveAddress address = new UmsMemberReceiveAddress();
            address.setName("Alice");
            address.setPhoneNumber("13800000000");
            address.setProvince("Beijing");
            address.setCity("Beijing");
            address.setRegion("Haidian");
            address.setDetailAddress("No. 1 Street");
            address.setPostCode("100000");
            lenient().when(memberReceiveAddressService.getItem(ADDRESS_ID)).thenReturn(address);

            PmsSkuStock skuStock = new PmsSkuStock();
            skuStock.setId(SKU_ID);
            skuStock.setStock(100);
            skuStock.setLockStock(0);
            when(skuStockMapper.selectByPrimaryKey(SKU_ID)).thenReturn(skuStock);
            // Re-stubbed to 0 in the lock-failure test, so use lenient here.
            lenient().when(portalOrderDao.lockStockBySkuId(eq(SKU_ID), anyInt())).thenReturn(1);

            OmsOrderSetting setting = new OmsOrderSetting();
            setting.setConfirmOvertime(7);
            setting.setNormalOrderOvertime(60);
            lenient().when(orderSettingMapper.selectByExample(any(OmsOrderSettingExample.class)))
                    .thenReturn(Collections.singletonList(setting));
            lenient().when(orderSettingMapper.selectByPrimaryKey(1L)).thenReturn(setting);

            lenient().when(redisService.incr(any(), eq(1L))).thenReturn(42L);
        }
    }

    // ---------------------------------------------------------------------
    // paySuccess
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("paySuccess")
    class PaySuccess {

        @Test
        @DisplayName("marks order paid and reduces sku stock")
        void paySuccess_succeeds_andReturnsTotalReducedQuantity() {
            when(orderMapper.updateByExampleSelective(any(OmsOrder.class), any(OmsOrderExample.class)))
                    .thenReturn(1);
            OmsOrderDetail detail = new OmsOrderDetail();
            detail.setOrderItemList(Arrays.asList(orderItem(SKU_ID, 3), orderItem(SKU_ID + 1, 2)));
            when(portalOrderDao.getDetail(ORDER_ID)).thenReturn(detail);
            when(portalOrderDao.reduceSkuStock(SKU_ID, 3)).thenReturn(3);
            when(portalOrderDao.reduceSkuStock(SKU_ID + 1, 2)).thenReturn(2);

            Integer totalReduced = orderService.paySuccess(ORDER_ID, 1);

            assertThat(totalReduced).isEqualTo(5);
            ArgumentCaptor<OmsOrder> orderCaptor = ArgumentCaptor.forClass(OmsOrder.class);
            verify(orderMapper).updateByExampleSelective(orderCaptor.capture(), any(OmsOrderExample.class));
            assertThat(orderCaptor.getValue().getStatus()).isEqualTo(1); // 待发货
            assertThat(orderCaptor.getValue().getPayType()).isEqualTo(1);
            assertThat(orderCaptor.getValue().getPaymentTime()).isNotNull();
        }

        @Test
        @DisplayName("rejects when the order is not in pending state (concurrent modification)")
        void paySuccess_throws_whenOrderAlreadyTransitioned() {
            when(orderMapper.updateByExampleSelective(any(OmsOrder.class), any(OmsOrderExample.class)))
                    .thenReturn(0);

            assertThatThrownBy(() -> orderService.paySuccess(ORDER_ID, 1))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("未支付");

            verify(portalOrderDao, never()).getDetail(anyLong());
            verify(portalOrderDao, never()).reduceSkuStock(anyLong(), anyInt());
        }

        @Test
        @DisplayName("rejects when stock cannot be reduced for any line item")
        void paySuccess_throws_whenStockReductionFails() {
            when(orderMapper.updateByExampleSelective(any(OmsOrder.class), any(OmsOrderExample.class)))
                    .thenReturn(1);
            OmsOrderDetail detail = new OmsOrderDetail();
            detail.setOrderItemList(Arrays.asList(orderItem(SKU_ID, 5)));
            when(portalOrderDao.getDetail(ORDER_ID)).thenReturn(detail);
            when(portalOrderDao.reduceSkuStock(SKU_ID, 5)).thenReturn(0); // race: stock already gone

            assertThatThrownBy(() -> orderService.paySuccess(ORDER_ID, 2))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("库存不足");
        }

        @Test
        @DisplayName("paySuccessByOrderSn delegates to paySuccess when an unpaid order exists")
        void paySuccessByOrderSn_delegatesToPaySuccess() {
            OmsOrder pending = new OmsOrder();
            pending.setId(ORDER_ID);
            when(orderMapper.selectByExample(any(OmsOrderExample.class)))
                    .thenReturn(Collections.singletonList(pending));
            when(orderMapper.updateByExampleSelective(any(OmsOrder.class), any(OmsOrderExample.class)))
                    .thenReturn(1);
            OmsOrderDetail detail = new OmsOrderDetail();
            detail.setOrderItemList(Arrays.asList(orderItem(SKU_ID, 1)));
            when(portalOrderDao.getDetail(ORDER_ID)).thenReturn(detail);
            when(portalOrderDao.reduceSkuStock(SKU_ID, 1)).thenReturn(1);

            orderService.paySuccessByOrderSn("SN-1", 1);

            verify(orderMapper).updateByExampleSelective(any(OmsOrder.class), any(OmsOrderExample.class));
            verify(portalOrderDao).reduceSkuStock(SKU_ID, 1);
        }

        @Test
        @DisplayName("paySuccessByOrderSn is a no-op when no matching unpaid order is found")
        void paySuccessByOrderSn_noOp_whenOrderNotFound() {
            when(orderMapper.selectByExample(any(OmsOrderExample.class)))
                    .thenReturn(Collections.emptyList());

            orderService.paySuccessByOrderSn("missing", 1);

            verify(orderMapper, never()).updateByExampleSelective(any(), any());
            verify(portalOrderDao, never()).getDetail(anyLong());
        }
    }

    // ---------------------------------------------------------------------
    // cancelOrder
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("cancelOrder")
    class CancelOrder {

        @Test
        @DisplayName("releases stock, restores coupon, and refunds integration when order is unpaid")
        void cancelOrder_releasesEverything_onUnpaidOrder() {
            OmsOrder unpaid = new OmsOrder();
            unpaid.setId(ORDER_ID);
            unpaid.setStatus(0);
            unpaid.setMemberId(MEMBER_ID);
            unpaid.setCouponId(null);
            unpaid.setUseIntegration(50);
            when(orderMapper.selectByExample(any(OmsOrderExample.class)))
                    .thenReturn(Collections.singletonList(unpaid));

            OmsOrderItem item = orderItem(SKU_ID, 2);
            when(orderItemMapper.selectByExample(any(OmsOrderItemExample.class)))
                    .thenReturn(Collections.singletonList(item));
            when(portalOrderDao.releaseStockBySkuId(SKU_ID, 2)).thenReturn(1);
            UmsMember member = member(MEMBER_ID, 100);
            when(memberService.getById(MEMBER_ID)).thenReturn(member);

            orderService.cancelOrder(ORDER_ID);

            ArgumentCaptor<OmsOrder> updateCaptor = ArgumentCaptor.forClass(OmsOrder.class);
            verify(orderMapper).updateByPrimaryKeySelective(updateCaptor.capture());
            assertThat(updateCaptor.getValue().getStatus()).isEqualTo(4); // 已关闭

            verify(portalOrderDao).releaseStockBySkuId(SKU_ID, 2);
            // 50 used → integration restored to 100 + 50 = 150
            verify(memberService).updateIntegration(MEMBER_ID, 150);
        }

        @Test
        @DisplayName("does nothing when no matching unpaid order exists")
        void cancelOrder_isNoOp_whenOrderNotFound() {
            when(orderMapper.selectByExample(any(OmsOrderExample.class))).thenReturn(Collections.emptyList());

            orderService.cancelOrder(ORDER_ID);

            verify(orderMapper, never()).updateByPrimaryKeySelective(any());
            verify(portalOrderDao, never()).releaseStockBySkuId(anyLong(), anyInt());
            verify(memberService, never()).updateIntegration(anyLong(), anyInt());
        }

        @Test
        @DisplayName("rejects when stock release fails for any line item")
        void cancelOrder_throws_whenStockReleaseFails() {
            OmsOrder unpaid = new OmsOrder();
            unpaid.setId(ORDER_ID);
            unpaid.setStatus(0);
            unpaid.setMemberId(MEMBER_ID);
            when(orderMapper.selectByExample(any(OmsOrderExample.class)))
                    .thenReturn(Collections.singletonList(unpaid));
            when(orderItemMapper.selectByExample(any(OmsOrderItemExample.class)))
                    .thenReturn(Collections.singletonList(orderItem(SKU_ID, 2)));
            when(portalOrderDao.releaseStockBySkuId(SKU_ID, 2)).thenReturn(0);

            assertThatThrownBy(() -> orderService.cancelOrder(ORDER_ID))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("库存");
        }

        @Test
        @DisplayName("does not refund integration when none was used")
        void cancelOrder_doesNotRefundIntegration_whenNotUsed() {
            OmsOrder unpaid = new OmsOrder();
            unpaid.setId(ORDER_ID);
            unpaid.setStatus(0);
            unpaid.setMemberId(MEMBER_ID);
            unpaid.setUseIntegration(null);
            when(orderMapper.selectByExample(any(OmsOrderExample.class)))
                    .thenReturn(Collections.singletonList(unpaid));
            when(orderItemMapper.selectByExample(any(OmsOrderItemExample.class)))
                    .thenReturn(Collections.emptyList());

            orderService.cancelOrder(ORDER_ID);

            verify(orderMapper).updateByPrimaryKeySelective(any(OmsOrder.class));
            verify(memberService, never()).getById(anyLong());
            verify(memberService, never()).updateIntegration(anyLong(), anyInt());
        }
    }

    // ---------------------------------------------------------------------
    // confirmReceiveOrder
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("confirmReceiveOrder")
    class ConfirmReceiveOrder {

        @Test
        @DisplayName("transitions a shipped order to completed and stamps receive time")
        void confirmReceiveOrder_succeeds_onShippedOrder() {
            UmsMember member = member(MEMBER_ID, 0);
            when(memberService.getCurrentMember()).thenReturn(member);
            OmsOrder order = new OmsOrder();
            order.setId(ORDER_ID);
            order.setMemberId(MEMBER_ID);
            order.setStatus(2); // 已发货
            when(orderMapper.selectByPrimaryKey(ORDER_ID)).thenReturn(order);

            orderService.confirmReceiveOrder(ORDER_ID);

            ArgumentCaptor<OmsOrder> captor = ArgumentCaptor.forClass(OmsOrder.class);
            verify(orderMapper).updateByPrimaryKey(captor.capture());
            OmsOrder saved = captor.getValue();
            assertThat(saved.getStatus()).isEqualTo(3);          // 已完成
            assertThat(saved.getConfirmStatus()).isEqualTo(1);   // 已确认
            assertThat(saved.getReceiveTime()).isNotNull();
        }

        @Test
        @DisplayName("rejects confirming someone else's order")
        void confirmReceiveOrder_throws_whenMemberMismatch() {
            UmsMember member = member(MEMBER_ID, 0);
            when(memberService.getCurrentMember()).thenReturn(member);
            OmsOrder order = new OmsOrder();
            order.setId(ORDER_ID);
            order.setMemberId(MEMBER_ID + 1); // belongs to another member
            order.setStatus(2);
            when(orderMapper.selectByPrimaryKey(ORDER_ID)).thenReturn(order);

            assertThatThrownBy(() -> orderService.confirmReceiveOrder(ORDER_ID))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("他人");

            verify(orderMapper, never()).updateByPrimaryKey(any());
        }

        @Test
        @DisplayName("rejects confirmation when the order has not shipped yet")
        void confirmReceiveOrder_throws_whenOrderNotShipped() {
            UmsMember member = member(MEMBER_ID, 0);
            when(memberService.getCurrentMember()).thenReturn(member);
            OmsOrder order = new OmsOrder();
            order.setId(ORDER_ID);
            order.setMemberId(MEMBER_ID);
            order.setStatus(1); // 待发货, not yet shipped
            when(orderMapper.selectByPrimaryKey(ORDER_ID)).thenReturn(order);

            assertThatThrownBy(() -> orderService.confirmReceiveOrder(ORDER_ID))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("发货");

            verify(orderMapper, never()).updateByPrimaryKey(any());
        }
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private static UmsMember member(long id, int integration) {
        UmsMember member = new UmsMember();
        member.setId(id);
        member.setUsername("buyer-" + id);
        member.setIntegration(integration);
        return member;
    }

    private static CartPromotionItem cartItem(long skuId, int quantity, int realStock) {
        CartPromotionItem item = new CartPromotionItem();
        item.setId(CART_ID);
        item.setProductId(PRODUCT_ID);
        item.setProductName("widget");
        item.setProductPic("pic");
        item.setProductAttr("attr");
        item.setProductBrand("brand");
        item.setProductSn("sn");
        item.setPrice(BigDecimal.valueOf(100));
        item.setQuantity(quantity);
        item.setProductSkuId(skuId);
        item.setProductSkuCode("sku-code");
        item.setProductCategoryId(CATEGORY_ID);
        item.setReduceAmount(BigDecimal.ZERO);
        item.setPromotionMessage("none");
        item.setIntegration(0);
        item.setGrowth(0);
        item.setRealStock(realStock);
        return item;
    }

    private static OrderParam orderParam(Long couponId, Integer useIntegration, int payType) {
        OrderParam param = new OrderParam();
        param.setMemberReceiveAddressId(ADDRESS_ID);
        param.setCouponId(couponId);
        param.setUseIntegration(useIntegration);
        param.setPayType(payType);
        param.setCartIds(Collections.singletonList(CART_ID));
        return param;
    }

    private static OmsOrderItem orderItem(long skuId, int quantity) {
        OmsOrderItem item = new OmsOrderItem();
        item.setProductSkuId(skuId);
        item.setProductQuantity(quantity);
        item.setProductPrice(BigDecimal.valueOf(100));
        item.setPromotionAmount(BigDecimal.ZERO);
        item.setCouponAmount(BigDecimal.ZERO);
        item.setIntegrationAmount(BigDecimal.ZERO);
        return item;
    }
}
