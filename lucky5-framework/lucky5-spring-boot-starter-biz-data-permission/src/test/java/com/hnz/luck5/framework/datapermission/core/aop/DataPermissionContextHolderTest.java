package com.hnz.luck5.framework.datapermission.core.aop;

import com.alibaba.ttl.TtlRunnable;
import com.hnz.luck5.framework.datapermission.core.annotation.DataPermission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

/**
 * {@link DataPermissionContextHolder} 的单元测试
 *
 * @author 芋道源码
 */
class DataPermissionContextHolderTest {

    @BeforeEach
    public void setUp() {
        DataPermissionContextHolder.clear();
    }

    @Test
    public void testGet() {
        // mock 方法
        DataPermission dataPermission01 = mock(DataPermission.class);
        DataPermissionContextHolder.add(dataPermission01);
        DataPermission dataPermission02 = mock(DataPermission.class);
        DataPermissionContextHolder.add(dataPermission02);

        // 调用
        DataPermission result = DataPermissionContextHolder.get();
        // 断言
        assertSame(result, dataPermission02);
    }

    @Test
    public void testPush() {
        // 调用
        DataPermission dataPermission01 = mock(DataPermission.class);
        DataPermissionContextHolder.add(dataPermission01);
        DataPermission dataPermission02 = mock(DataPermission.class);
        DataPermissionContextHolder.add(dataPermission02);
        // 断言
        DataPermission first = DataPermissionContextHolder.getAll().get(0);
        DataPermission second = DataPermissionContextHolder.getAll().get(1);
        assertSame(dataPermission01, first);
        assertSame(dataPermission02, second);
    }

    @Test
    public void testRemove() {
        // mock 方法
        DataPermission dataPermission01 = mock(DataPermission.class);
        DataPermissionContextHolder.add(dataPermission01);
        DataPermission dataPermission02 = mock(DataPermission.class);
        DataPermissionContextHolder.add(dataPermission02);

        // 调用
        DataPermission result = DataPermissionContextHolder.remove();
        // 断言
        assertSame(result, dataPermission02);
        assertEquals(1, DataPermissionContextHolder.getAll().size());
    }

    @Test
    public void transmittedStackIsIndependentOfParent() throws Exception {
        DataPermission permission = mock(DataPermission.class);
        DataPermissionContextHolder.add(permission);
        FutureTask<DataPermission> task = new FutureTask<>(() -> {
            DataPermission inherited = DataPermissionContextHolder.get();
            DataPermissionContextHolder.remove();
            return inherited;
        });

        Thread worker = new Thread(TtlRunnable.get(task));
        worker.start();

        assertSame(permission, task.get(5, TimeUnit.SECONDS));
        assertSame(permission, DataPermissionContextHolder.get());
        assertEquals(1, DataPermissionContextHolder.getAll().size());
    }

}
