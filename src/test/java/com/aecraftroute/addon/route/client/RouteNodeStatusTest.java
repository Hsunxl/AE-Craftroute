package com.aecraftroute.addon.route.client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RouteNodeStatusTest {
    @Test void statusIsSeparateFromNumbersIconsAndNameAndFitsTheCard() {
        var row=RouteNodeStatus.of(10,20,86);var quantity=RouteQuantityField.of(10,20,86);
        assertTrue(row.y()>=quantity.y()+quantity.height());assertTrue(row.y()>=20+RouteQuantityField.NAME_Y+9);
        assertTrue(row.y()+row.height()<=20+RouteQuantityField.NODE_HEIGHT);
        assertTrue(row.contains(row.x(),row.y()));assertFalse(row.contains(row.x()+row.width(),row.y()));
    }
    @Test void collapsedExpandedRootAndCycleHaveConciseLabels() {
        assertEquals("已展开 ▾",RouteNodeStatus.label(false,false,true,true,false));
        assertEquals("已折叠 ▸",RouteNodeStatus.label(false,false,true,false,false));
        assertEquals("最终产物 ▾",RouteNodeStatus.label(true,false,true,true,false));
        assertEquals("回料引用 ▾",RouteNodeStatus.label(false,true,false,true,false));
        assertEquals("选择获取方式 ▾",RouteNodeStatus.label(false,false,false,true,false));
    }
    @Test void emcStillHasAnOnCardAffordanceButDoesNotReplaceBranchState() {
        assertEquals("已展开 · EMC ▾",RouteNodeStatus.label(false,false,true,true,true));
        assertTrue(RouteNodeStatus.label(false,false,true,false,true).contains("已折叠"));
    }
    @Test void detailsPreserveProofCapitalCostAndLocalEditWarnings() {
        var data=new RouteNodeStatus.Details("沙子",1152,false,true,true,2,"网络计划已证明","15",true,"网络 EMC 1152","测试错误");
        var lines=RouteNodeStatus.details(data);
        assertTrue(lines.contains("需求：1152"));assertTrue(lines.stream().anyMatch(s->s.contains("不是库存快照")));
        assertTrue(lines.stream().anyMatch(s->s.contains("本地编辑不等于")));assertTrue(lines.stream().anyMatch(s->s.contains("网络 EMC 1152")));
        assertTrue(lines.contains("测试错误"));
    }
    @Test void noProofAndFoldedBranchesNeverPretendToBeMissingOrEmpty() {
        var data=new RouteNodeStatus.Details("a",1,false,true,false,0,null,null,false,null,null);
        var lines=RouteNodeStatus.details(data);
        assertTrue(lines.stream().anyMatch(s->s.contains("配方与数量保留")));
        assertTrue(lines.stream().anyMatch(s->s.contains("尚未取得服务端证明")));
        assertFalse(lines.stream().anyMatch(s->s.contains("缺料")||s.contains("可见下级 0")));
    }
}
