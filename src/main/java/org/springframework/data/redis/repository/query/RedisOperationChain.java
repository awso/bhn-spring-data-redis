/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.redis.repository.query;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisZSetCommands.Range;
import org.springframework.util.ObjectUtils;

/**
 * Simple set of operations required to run queries against Redis.
 * 
 * @author Christoph Strobl
 * @since 1.7
 */
public class RedisOperationChain {

	private Set<PathAndValue> sismember = new LinkedHashSet<PathAndValue>();
	private Set<Set<PathAndValue>> orSismember = new LinkedHashSet<>();
	private NearPath near;
	private Set<PathAndValue> ranges = new LinkedHashSet<PathAndValue>();
	
	public void ranges(String path, Range range){
	    ranges.add(new PathAndValue(path, range));
	}

	public Set<PathAndValue> getRanges(){
	    return ranges;
	}

    public void sismember(String path, Object value) {
		sismember(new PathAndValue(path, value));
	}

	public void sismember(PathAndValue pathAndValue) {
		sismember.add(pathAndValue);
	}

	public Set<PathAndValue> getSismember() {
		return sismember;
	}

	public void orSismember(String path, Object value) {
		orSismember(new PathAndValue(path, value));
	}

	public void orSismember(PathAndValue pathAndValue) {
	    Set<PathAndValue> set = new LinkedHashSet<PathAndValue>();
	    set.add(pathAndValue);
		orSismember.add(set);
	}

	public void orSismember(Collection<PathAndValue> next) {
		orSismember.add((Set<PathAndValue>) next);
	}

	public Set<Set<PathAndValue>> getOrSismember() {
		return orSismember;
	}

	public void near(NearPath near) {
		this.near = near;
	}

	public NearPath getNear() {
		return near;
	}

	public static class PathAndValue {

		private final String path;
		private final Collection<Object> values;

		public PathAndValue(String path, Object singleValue) {

			this.path = path;
			this.values = Collections.singleton(singleValue);
		}

		public PathAndValue(String path, Collection<Object> values) {

			this.path = path;
			this.values = values != null ? values : Collections.emptySet();
		}

		public boolean isSingleValue() {
			return values.size() == 1;
		}

		public String getPath() {
			return path;
		}

		public Collection<Object> values() {
			return values;
		}

		public Object getFirstValue() {
			return values.isEmpty() ? null : values.iterator().next();
		}

		@Override
		public String toString() {
			return path + ":" + (isSingleValue() ? getFirstValue() : values);
		}

		@Override
		public int hashCode() {

			int result = ObjectUtils.nullSafeHashCode(path);
			result += ObjectUtils.nullSafeHashCode(values);
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (obj == null) {
				return false;
			}
			if (!(obj instanceof PathAndValue)) {
				return false;
			}
			PathAndValue that = (PathAndValue) obj;
			if (!ObjectUtils.nullSafeEquals(this.path, that.path)) {
				return false;
			}

			return ObjectUtils.nullSafeEquals(this.values, that.values);
		}

	}

	/**
	 * @since 1.8
	 * @author Christoph Strobl
	 */
	public static class NearPath extends PathAndValue {

		public NearPath(String path, Point point, Distance distance) {
			super(path, Arrays.<Object> asList(point, distance));
		}

		public Point getPoint() {
			return (Point) getFirstValue();
		}

		public Distance getDistance() {

			Iterator<Object> it = values().iterator();
			it.next();
			return (Distance) it.next();
		}
	}
	
//	public static class Range extends PathAndValue{
//	    public double min = Double.MIN_VALUE;
//	    public double max = Double.MAX_VALUE;
//
//        public Range(String path, Object singleValue) {
//            super(path, singleValue);
//            // TODO Auto-generated constructor stub
//        }
//	    
//	}
}
