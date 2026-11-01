package com.libramind.catalog;

/** 在馆藏业务事务提交前同步登记知识索引任务，editionId 是变更的出版版本。 */
public record CatalogChanged(long editionId) {}
