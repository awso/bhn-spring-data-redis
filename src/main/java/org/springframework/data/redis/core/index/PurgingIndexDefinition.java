package org.springframework.data.redis.core.index;

public class PurgingIndexDefinition<T> extends SortingIndexDefinition implements PathBasedRedisIndexDefinition {
    private final IndexNameHandler<T> indexNameHandler;

    public PurgingIndexDefinition(String keyspace, String path, IndexNameHandler<T> handler) {
        super(keyspace, path, null);
        this.indexNameHandler = handler;
    }

    public String getIndexName(T value){
        return indexNameHandler.getIndexName(value);
    }

}
