package com.aecraftroute.addon.route;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/** Metadata-only provider search: filter the complete device list before taking a page. */
public final class RouteProviderFilter {
    public static final int MAX_QUERY_LENGTH=128;
    public record Metadata(String name,String modId,String itemId,String location) {}
    public record Page<T>(List<T> items,int page,int pages,int total) {}
    private RouteProviderFilter() {}

    public static String boundedQuery(String query) {
        if(query==null)return "";
        if(query.length()>MAX_QUERY_LENGTH)throw new IllegalArgumentException("供应器搜索最多 128 个字符");
        return query.trim();
    }
    public static String normalize(String query) {return boundedQuery(query).toLowerCase(Locale.ROOT);}
    private static String text(String value) {return value==null?"":value.toLowerCase(Locale.ROOT);}
    public static boolean matches(Metadata metadata,String query) {
        return matchesNormalized(metadata,normalize(query));
    }
    private static boolean matchesNormalized(Metadata metadata,String normalized) {
        return normalized.isEmpty() || metadata!=null && (text(metadata.name()).contains(normalized)
                || text(metadata.modId()).contains(normalized) || text(metadata.itemId()).contains(normalized)
                || text(metadata.location()).contains(normalized));
    }
    public static <T> Page<T> select(List<T> providers,Function<T,Metadata> metadata,String query,int requestedPage,int perPage) {
        if(perPage<1)throw new IllegalArgumentException("供应器分页大小无效");
        String normalized=normalize(query);
        var filtered=new ArrayList<T>();
        for(T provider:providers)if(normalized.isEmpty() || matchesNormalized(metadata.apply(provider),normalized))filtered.add(provider);
        int pages=(int)(((long)filtered.size()+perPage-1)/perPage);
        int page=pages==0?0:Math.max(0,Math.min(requestedPage,pages-1));
        int start=page*perPage;
        return new Page<>(List.copyOf(filtered.subList(start,Math.min(start+perPage,filtered.size()))),page,pages,filtered.size());
    }
}
