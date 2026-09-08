package com.bbb.exercise.agentdemo1_0.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 通用分页结果。
 *
 * @param <T> 记录类型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PageResult<T> {

    /** 当前页数据 */
    private List<T> records;

    /** 总条数 */
    private long total;

    /** 当前页码（从 1 开始） */
    private int page;

    /** 每页条数 */
    private int size;

    /** 总页数 */
    private int totalPages;

    /**
     * 快速构造分页结果。
     *
     * @param records 当前页数据
     * @param total   总条数
     * @param page    当前页码（1-based）
     * @param size    每页条数
     */
    public static <T> PageResult<T> of(List<T> records, long total, int page, int size) {
        int totalPages = size <= 0 ? 0 : (int) Math.ceil((double) total / size);
        return PageResult.<T>builder()
                .records(records == null ? List.of() : records)
                .total(total)
                .page(page)
                .size(size)
                .totalPages(totalPages)
                .build();
    }
}
