package org.springframework.data.redis.core.index;

public interface IndexValueHandler<Input> {
    public Double getValue(Input input); 
}
