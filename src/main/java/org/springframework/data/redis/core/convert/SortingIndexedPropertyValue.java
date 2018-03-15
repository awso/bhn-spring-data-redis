package org.springframework.data.redis.core.convert;

import org.springframework.data.redis.core.convert.IndexedData;

public class SortingIndexedPropertyValue implements IndexedData {

    private final String keyspace;
    private final String indexName;
    private final double  score;
//    private final Object value;
//    private Callback<T> func;
    
    public SortingIndexedPropertyValue(String keyspace, String indexName, Object value){
        this.keyspace = keyspace;
        this.indexName = indexName;
//        this.value = value;
        this.score = (Double) value;
    }
    
    @Override
    public String getIndexName() {
        return indexName;
    }

    @Override
    public String getKeyspace() {
        return keyspace;
    }

//    public Object getValue(){
//        return value;
//    }

    public double getScore() {
        return score;
    }
}
