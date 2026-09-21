package com.tvbox.android44.data.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.tvbox.android44.domain.model.PlatformLive;

import org.junit.Test;

import java.util.List;

/** 平台分类响应：父分类、完整分类表和无父分类旧形状。 */
public class PlatformLiveClientTest {

    @Test
    public void parentCategories_areUsedForRootLevel() throws Exception {
        String json = "{\"parentCategories\":["
                + "{\"id\":\"1\",\"name\":\"网游\",\"cover\":\"c\"}],"
                + "\"categories\":["
                + "{\"id\":\"1.0\",\"name\":\"英雄联盟\",\"parentId\":\"1\"}]}";
        List<PlatformLive.Category> result = PlatformLiveClient.parseCategoriesBody(json, null);
        assertEquals(1, result.size());
        assertEquals("1", result.get(0).id);
        assertEquals("", result.get(0).parentId);
        assertEquals("c", result.get(0).iconUrl);
    }

    @Test
    public void childCategories_areFilteredWhenServerReturnsFullTable() throws Exception {
        String json = "{\"parentCategories\":[{\"id\":\"1\",\"name\":\"网游\"}],"
                + "\"categories\":["
                + "{\"id\":\"1.0\",\"name\":\"英雄联盟\",\"parentId\":\"1\"},"
                + "{\"id\":\"2.0\",\"name\":\"单机\",\"parentId\":\"2\"}]}";
        List<PlatformLive.Category> result = PlatformLiveClient.parseCategoriesBody(json, "1");
        assertEquals(1, result.size());
        assertEquals("1.0", result.get(0).id);
    }

    @Test
    public void categoriesOnlyShape_isKeptForPlatformsWithoutParents() throws Exception {
        String json = "{\"categories\":[{\"id\":\"x\",\"name\":\"综合\"}]}";
        List<PlatformLive.Category> result = PlatformLiveClient.parseCategoriesBody(json, null);
        assertEquals(1, result.size());
        assertTrue(result.get(0).parentId.isEmpty());
    }
}
