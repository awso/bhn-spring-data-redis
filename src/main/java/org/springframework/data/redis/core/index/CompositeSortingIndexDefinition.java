package org.springframework.data.redis.core.index;

public class CompositeSortingIndexDefinition<T> extends SortingIndexDefinition {
    public IndexNameHandler<T> indexNameHandler;
    public IndexValueHandler<T> indexValueHandler;

    public CompositeSortingIndexDefinition(String keyspace, String path, IndexNameHandler<T> indexNameHandler, IndexValueHandler<T> indexValueHandler){
        super(keyspace, path, indexNameHandler.getIndexNamePlaceHolder());
        this.indexNameHandler = indexNameHandler;
        this.indexValueHandler = indexValueHandler;
    }

    public String getIndexName(T obj) {
        T typedObj = obj;
        //TODO: error handling in case casting failed
        //TODO: error handing in case get index name failed
        return indexNameHandler.getIndexName(typedObj);
    }

    public Object getIndexValue(T value) {
        T typedValue = (T) value;
        return indexValueHandler.getValue(typedValue);
    }
    
    public static class TopLevelCompositeSortingIndexDefition<T> extends CompositeSortingIndexDefinition<T>{

        public TopLevelCompositeSortingIndexDefition(String keyspace, IndexNameHandler<T> indexNameHandler,
                IndexValueHandler<T> indexValueHandler) {
            super(keyspace, "", indexNameHandler, indexValueHandler);
        }
        
    }

}
