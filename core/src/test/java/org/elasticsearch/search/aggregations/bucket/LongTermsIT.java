/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.search.aggregations.bucket;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.fielddata.ScriptDocValues;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptService.ScriptType;
import org.elasticsearch.search.aggregations.AggregationTestScriptsPlugin;
import org.elasticsearch.search.aggregations.Aggregator.SubAggCollectionMode;
import org.elasticsearch.search.aggregations.bucket.StringTermsIT.CustomScriptPlugin;
import org.elasticsearch.search.aggregations.bucket.filter.Filter;
import org.elasticsearch.search.aggregations.bucket.histogram.Histogram;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.aggregations.bucket.terms.support.IncludeExclude;
import org.elasticsearch.search.aggregations.metrics.avg.Avg;
import org.elasticsearch.search.aggregations.metrics.max.Max;
import org.elasticsearch.search.aggregations.metrics.stats.Stats;
import org.elasticsearch.search.aggregations.metrics.stats.extended.ExtendedStats;
import org.elasticsearch.search.aggregations.metrics.sum.Sum;
import org.elasticsearch.test.ESIntegTestCase;
import org.hamcrest.Matchers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.index.query.QueryBuilders.matchAllQuery;
import static org.elasticsearch.search.aggregations.AggregationBuilders.avg;
import static org.elasticsearch.search.aggregations.AggregationBuilders.extendedStats;
import static org.elasticsearch.search.aggregations.AggregationBuilders.filter;
import static org.elasticsearch.search.aggregations.AggregationBuilders.histogram;
import static org.elasticsearch.search.aggregations.AggregationBuilders.max;
import static org.elasticsearch.search.aggregations.AggregationBuilders.stats;
import static org.elasticsearch.search.aggregations.AggregationBuilders.sum;
import static org.elasticsearch.search.aggregations.AggregationBuilders.terms;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertSearchResponse;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.core.IsNull.notNullValue;

@ESIntegTestCase.SuiteScopeTestCase
public class LongTermsIT extends AbstractTermsTestCase {

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return Collections.singleton(CustomScriptPlugin.class);
    }

    public static class CustomScriptPlugin extends AggregationTestScriptsPlugin {

        @Override
        @SuppressWarnings("unchecked")
        protected Map<String, Function<Map<String, Object>, Object>> pluginScripts() {
            Map<String, Function<Map<String, Object>, Object>> scripts = super.pluginScripts();

            scripts.put("floor(_value / 1000 + 1)", vars -> Math.floor((double) vars.get("_value") / 1000 + 1));

            scripts.put("doc['" + MULTI_VALUED_FIELD_NAME + "']", vars -> {
                Map<?, ?> doc = (Map) vars.get("doc");
                return doc.get(MULTI_VALUED_FIELD_NAME);
            });

            scripts.put("doc['" + SINGLE_VALUED_FIELD_NAME + "'].value", vars -> {
                Map<?, ?> doc = (Map) vars.get("doc");
                ScriptDocValues.Longs value = (ScriptDocValues.Longs) doc.get(SINGLE_VALUED_FIELD_NAME);
                return value.getValue();
            });

            return scripts;
        }
    }

    private static final int NUM_DOCS = 5; // TODO randomize the size?
    private static final String SINGLE_VALUED_FIELD_NAME = "l_value";
    private static final String MULTI_VALUED_FIELD_NAME = "l_values";
    private static HashMap<Long, Map<String, Object>> expectedMultiSortBuckets;

    @Override
    public void setupSuiteScopeCluster() throws Exception {
        createIndex("idx");
        IndexRequestBuilder[] lowCardBuilders = new IndexRequestBuilder[NUM_DOCS];
        for (int i = 0; i < lowCardBuilders.length; i++) {
            lowCardBuilders[i] = client().prepareIndex("idx", "type").setSource(jsonBuilder()
                    .startObject()
                    .field(SINGLE_VALUED_FIELD_NAME, i)
                    .startArray(MULTI_VALUED_FIELD_NAME).value(i).value(i + 1).endArray()
                    .field("num_tag", i < lowCardBuilders.length / 2 + 1 ? 1 : 0) // used to test order by single-bucket sub agg
                    .endObject());
        }
        indexRandom(randomBoolean(), lowCardBuilders);
        IndexRequestBuilder[] highCardBuilders = new IndexRequestBuilder[100]; // TODO randomize the size?
        for (int i = 0; i < highCardBuilders.length; i++) {
           highCardBuilders[i] = client().prepareIndex("idx", "high_card_type").setSource(jsonBuilder()
                    .startObject()
                    .field(SINGLE_VALUED_FIELD_NAME, i)
                    .startArray(MULTI_VALUED_FIELD_NAME).value(i).value(i + 1).endArray()
                    .endObject());

        }
        indexRandom(true, highCardBuilders);
        createIndex("idx_unmapped");

        assertAcked(prepareCreate("empty_bucket_idx").addMapping("type", SINGLE_VALUED_FIELD_NAME, "type=integer"));
        List<IndexRequestBuilder> builders = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            builders.add(client().prepareIndex("empty_bucket_idx", "type", ""+i).setSource(jsonBuilder()
                    .startObject()
                    .field(SINGLE_VALUED_FIELD_NAME, i * 2)
                    .endObject()));
        }

        getMultiSortDocs(builders);

        indexRandom(true, builders.toArray(new IndexRequestBuilder[builders.size()]));
        ensureSearchable();
    }

    private void getMultiSortDocs(List<IndexRequestBuilder> builders) throws IOException {
        expectedMultiSortBuckets = new HashMap<>();
        Map<String, Object> bucketProps = new HashMap<>();
        bucketProps.put("_term", 1L);
        bucketProps.put("_count", 3L);
        bucketProps.put("avg_l", 1d);
        bucketProps.put("sum_d", 6d);
        expectedMultiSortBuckets.put((Long) bucketProps.get("_term"), bucketProps);
        bucketProps = new HashMap<>();
        bucketProps.put("_term", 2L);
        bucketProps.put("_count", 3L);
        bucketProps.put("avg_l", 2d);
        bucketProps.put("sum_d", 6d);
        expectedMultiSortBuckets.put((Long) bucketProps.get("_term"), bucketProps);
        bucketProps = new HashMap<>();
        bucketProps.put("_term", 3L);
        bucketProps.put("_count", 2L);
        bucketProps.put("avg_l", 3d);
        bucketProps.put("sum_d", 3d);
        expectedMultiSortBuckets.put((Long) bucketProps.get("_term"), bucketProps);
        bucketProps = new HashMap<>();
        bucketProps.put("_term", 4L);
        bucketProps.put("_count", 2L);
        bucketProps.put("avg_l", 3d);
        bucketProps.put("sum_d", 4d);
        expectedMultiSortBuckets.put((Long) bucketProps.get("_term"), bucketProps);
        bucketProps = new HashMap<>();
        bucketProps.put("_term", 5L);
        bucketProps.put("_count", 2L);
        bucketProps.put("avg_l", 5d);
        bucketProps.put("sum_d", 3d);
        expectedMultiSortBuckets.put((Long) bucketProps.get("_term"), bucketProps);
        bucketProps = new HashMap<>();
        bucketProps.put("_term", 6L);
        bucketProps.put("_count", 1L);
        bucketProps.put("avg_l", 5d);
        bucketProps.put("sum_d", 1d);
        expectedMultiSortBuckets.put((Long) bucketProps.get("_term"), bucketProps);
        bucketProps = new HashMap<>();
        bucketProps.put("_term", 7L);
        bucketProps.put("_count", 1L);
        bucketProps.put("avg_l", 5d);
        bucketProps.put("sum_d", 1d);
        expectedMultiSortBuckets.put((Long) bucketProps.get("_term"), bucketProps);

        createIndex("sort_idx");
        for (int i = 1; i <= 3; i++) {
            builders.add(client().prepareIndex("sort_idx", "multi_sort_type").setSource(jsonBuilder()
                    .startObject()
                    .field(SINGLE_VALUED_FIELD_NAME, 1)
                    .field("l", 1)
                    .field("d", i)
                    .endObject()));
            builders.add(client().prepareIndex("sort_idx", "multi_sort_type").setSource(jsonBuilder()
                    .startObject()
                    .field(SINGLE_VALUED_FIELD_NAME, 2)
                    .field("l", 2)
                    .field("d", i)
                    .endObject()));
        }
        builders.add(client().prepareIndex("sort_idx", "multi_sort_type").setSource(jsonBuilder()
                .startObject()
                .field(SINGLE_VALUED_FIELD_NAME, 3)
                .field("l", 3)
                .field("d", 1)
                .endObject()));
        builders.add(client().prepareIndex("sort_idx", "multi_sort_type").setSource(jsonBuilder()
                .startObject()
                .field(SINGLE_VALUED_FIELD_NAME, 3)
                .field("l", 3)
                .field("d", 2)
                .endObject()));
        builders.add(client().prepareIndex("sort_idx", "multi_sort_type").setSource(jsonBuilder()
                .startObject()
                .field(SINGLE_VALUED_FIELD_NAME, 4)
                .field("l", 3)
                .field("d", 1)
                .endObject()));
        builders.add(client().prepareIndex("sort_idx", "multi_sort_type").setSource(jsonBuilder()
                .startObject()
                .field(SINGLE_VALUED_FIELD_NAME, 4)
                .field("l", 3)
                .field("d", 3)
                .endObject()));
        builders.add(client().prepareIndex("sort_idx", "multi_sort_type").setSource(jsonBuilder()
                .startObject()
                .field(SINGLE_VALUED_FIELD_NAME, 5)
                .field("l", 5)
                .field("d", 1)
                .endObject()));
        builders.add(client().prepareIndex("sort_idx", "multi_sort_type").setSource(jsonBuilder()
                .startObject()
                .field(SINGLE_VALUED_FIELD_NAME, 5)
                .field("l", 5)
                .field("d", 2)
                .endObject()));
        builders.add(client().prepareIndex("sort_idx", "multi_sort_type").setSource(jsonBuilder()
                .startObject()
                .field(SINGLE_VALUED_FIELD_NAME, 6)
                .field("l", 5)
                .field("d", 1)
                .endObject()));
        builders.add(client().prepareIndex("sort_idx", "multi_sort_type").setSource(jsonBuilder()
                .startObject()
                .field(SINGLE_VALUED_FIELD_NAME, 7)
                .field("l", 5)
                .field("d", 1)
                .endObject()));
    }

    private String key(Terms.Bucket bucket) {
        return bucket.getKeyAsString();
    }

    // the main purpose of this test is to make sure we're not allocating 2GB of memory per shard
    public void testSizeIsZero() {
        IllegalArgumentException exception = expectThrows(IllegalArgumentException.class,
                () -> client().prepareSearch("idx").setTypes("high_card_type")
                .addAggregation(terms("terms")
                        .field(SINGLE_VALUED_FIELD_NAME)
                        .collectMode(randomFrom(SubAggCollectionMode.values()))
                        .minDocCount(randomInt(1))
                        .size(0))
                        .execute().actionGet());
        assertThat(exception.getMessage(), containsString("[size] must be greater than 0. Found [0] in [terms]"));
    }

    public void testSingleValueField() throws Exception {
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(terms("terms")
                        .field(SINGLE_VALUED_FIELD_NAME)
                        .collectMode(randomFrom(SubAggCollectionMode.values())))
                .execute().actionGet();

        assertSearchResponse(response);


        Terms terms = response.getAggregations().get("terms");
        assertThat(terms, notNullValue());
        assertThat(terms.getName(), equalTo("terms"));
        assertThat(terms.getBuckets().size(), equalTo(5));

        for (int i = 0; i < 5; i++) {
            Terms.Bucket bucket = terms.getBucketByKey("" + i);
            assertThat(bucket, notNullValue());
            assertThat(key(bucket), equalTo("" + i));
            assertThat(bucket.getKeyAsNumber().intValue(), equalTo(i));
            assertThat(bucket.getDocCount(), equalTo(1L));
        }
    }

    public void testSingleValueFieldWithFiltering() throws Exception {
        long includes[] = { 1, 2, 3, 98 };
        long excludes[] = { -1, 2, 4 };
        long empty[] = {};
        testIncludeExcludeResults(includes, empty, new long[] { 1, 2, 3 });
        testIncludeExcludeResults(includes, excludes, new long[] { 1, 3 });
        testIncludeExcludeResults(empty, excludes, new long[] { 0, 1, 3 });
    }

    private void testIncludeExcludeResults(long[] includes, long[] excludes, long[] expecteds) {
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(terms("terms")
                        .field(SINGLE_VALUED_FIELD_NAME)
                        .includeExclude(new IncludeExclude(includes, excludes))
                        .collectMode(randomFrom(SubAggCollectionMode.values())))
                .execute().actionGet();
        assertSearchResponse(response);
        Terms terms = response.getAggregations().get("terms");
        assertThat(terms, notNullValue());
        assertThat(terms.getName(), equalTo("terms"));
        assertThat(terms.getBuckets().size(), equalTo(expecteds.length));

        for (int i = 0; i < expecteds.length; i++) {
            Terms.Bucket bucket = terms.getBucketByKey("" + expecteds[i]);
            assertThat(bucket, notNullValue());
            assertThat(bucket.getDocCount(), equalTo(1L));
        }
    }

    public void testSingleValueFieldWithMaxSize() throws Exception {
        SearchResponse response = client().prepareSearch("idx").setTypes("high_card_type")
                .addAggregation(terms("terms")
                        .field(SINGLE_VALUED_FIELD_NAME)
                        .size(20)
                        .collectMode(randomFrom(SubAggCollectionMode.values()))
                        .order(Terms.Order.term(true))) // we need to sort by terms cause we're checking the first 20 values
                .execute().actionGet();

        assertSearchResponse(response);


        Terms terms = response.getAggregations().get("terms");
        assertThat(terms, notNullValue());
        assertThat(terms.getName(), equalTo("terms"));
        assertThat(terms.getBuckets().size(), equalTo(20));

        for (int i = 0; i < 20; i++) {
            Terms.Bucket bucket = terms.getBucketByKey("" + i);
            assertThat(bucket, notNullValue());
            assertThat(key(bucket), equalTo("" + i));
            assertThat(bucket.getKeyAsNumber().intValue(), equalTo(i));
            assertThat(bucket.getDocCount(), equalTo(1L));
        }
    }

    public void testSingleValueFieldOrderedByTermAsc() throws Exception {
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(terms("terms")
                        .field(SINGLE_VALUED_FIELD_NAME)
                        .collectMode(randomFrom(SubAggCollectionMode.values()))
                        .order(Terms.Order.term(true)))
                .execute().actionGet();
        assertSearchResponse(response);

        Terms terms = response.getAggregations().get("terms");
        assertThat(terms, notNullValue());
        assertThat(terms.getName(), equalTo("terms"));
        assertThat(terms.getBuckets().size(), equalTo(5));

        int i = 0;
        for (Terms.Bucket bucket : terms.getBuckets()) {
            assertThat(bucket, notNullValue());
            assertThat(key(bucket), equalTo("" + i));
            assertThat(bucket.getKeyAsNumber().intValue(), equalTo(i));
            assertThat(bucket.getDocCount(), equalTo(1L));
            i++;
        }
    }

    public void testSingleValueFieldOrderedByTermDesc() throws Exception {
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(terms("terms")
                        .field(SINGLE_VALUED_FIELD_NAME)
                        .collectMode(randomFrom(SubAggCollectionMode.values()))
                        .order(Terms.Order.term(false)))
                .execute().actionGet();

        assertSearchResponse(response);


        Terms terms = response.getAggregations().get("terms");
        assertThat(terms, notNullValue());
        assertThat(terms.getName(), equalTo("terms"));
        assertThat(terms.getBuckets().size(), equalTo(5));

        int i = 4;
        for (Terms.Bucket bucket : terms.getBuckets()) {
            assertThat(bucket, notNullValue());
            assertThat(key(bucket), equalTo("" + i));
            assertThat(bucket.getKeyAsNumber().intValue(), equalTo(i));
            assertThat(bucket.getDocCount(), equalTo(1L));
            i--;
        }
    }

    public void testSingleValuedFieldWithSubAggregation() throws Exception {
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(terms("terms")
                        .field(SINGLE_VALUED_FIELD_NAME)
                        .collectMode(randomFrom(SubAggCollectionMode.values()))
                        .subAggregation(sum("sum").field(MULTI_VALUED_FIELD_NAME)))
                .execute().actionGet();

        assertSearchResponse(response);


        Terms terms = response.getAggregations().get("terms");
        assertThat(terms, notNullValue());
        assertThat(terms.getName(), equalTo("terms"));
        assertThat(terms.getBuckets().size(), equalTo(5));
        Object[] propertiesKeys = (Object[]) terms.getProperty("_key");
        Object[] propertiesDocCounts = (Object[]) terms.getProperty("_count");
        Object[] propertiesCounts = (Object[]) terms.getProperty("sum.value");

        for (int i = 0; i < 5; i++) {
            Terms.Bucket bucket = terms.getBucketByKey("" + i);
            assertThat(bucket, notNullValue());
            assertThat(key(bucket), equalTo("" + i));
            assertThat(bucket.getKeyAsNumber().intValue(), equalTo(i));
            assertThat(bucket.getDocCount(), equalTo(1L));
            Sum sum = bucket.getAggregations().get("sum");
            assertThat(sum, notNullValue());
            assertThat((long) sum.getValue(), equalTo(i+i+1L));
            assertThat((long) propertiesKeys[i], equalTo((long) i));
            assertThat((long) propertiesDocCounts[i], equalTo(1L));
            assertThat((double) propertiesCounts[i], equalTo((double) i + i + 1L));
        }
    }

    public void testSingleValuedFieldWithValueScript() throws Exception {
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(terms("terms")
                        .field(SINGLE_VALUED_FIELD_NAME)
                        .collectMode(randomFrom(SubAggCollectionMode.values()))
                        .script(new Script("_value + 1", ScriptType.INLINE, CustomScriptPlugin.NAME, null)))
                .get();

        assertSearchResponse(response);


        Terms terms = response.getAggregations().get("terms");
        assertThat(terms, notNullValue());
        assertThat(terms.getName(), equalTo("terms"));
        assertThat(terms.getBuckets().size(), equalTo(5));

        for (int i = 0; i < 5; i++) {
            Terms.Bucket bucket = terms.getBucketByKey("" + (i + 1d));
            assertThat(bucket, notNullValue());
            assertThat(key(bucket), equalTo("" + (i+1d)));
            assertThat(bucket.getKeyAsNumber().intValue(), equalTo(i+1));
            assertThat(bucket.getDocCount(), equalTo(1L));
        }
    }

    public void testMultiValuedField() throws Exception {
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(terms("terms")
                        .field(MULTI_VALUED_FIELD_NAME)
                        .collectMode(randomFrom(SubAggCollectionMode.values())))
                .execute().actionGet();

        assertSearchResponse(response);


        Terms terms = response.getAggregations().get("terms");
        assertThat(terms, notNullValue());
        assertThat(terms.getName(), equalTo("terms"));
        assertThat(terms.getBuckets().size(), equalTo(6));

        for (int i = 0; i < 6; i++) {
            Terms.Bucket bucket = terms.getBucketByKey("" + i);
            assertThat(bucket, notNullValue());
            assertThat(key(bucket), equalTo("" + i));
            assertThat(bucket.getKeyAsNumber().intValue(), equalTo(i));
            if (i == 0 || i == 5) {
                assertThat(bucket.getDocCount(), equalTo(1L));
            } else {
                assertThat(bucket.getDocCount(), equalTo(2L));
            }
        }
    }

    public void testMultiValuedFieldWithValueScript() throws Exception {
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(terms("terms")
                        .field(MULTI_VALUED_FIELD_NAME)
                        .collectMode(randomFrom(SubAggCollectionMode.values()))
                                .script(new Script("_value - 1", ScriptType.INLINE, CustomScriptPlugin.NAME, null)))
                .get();

        assertSearchResponse(response);


        Terms terms = response.getAggregations().get("terms");
        assertThat(terms, notNullValue());
        assertThat(terms.getName(), equalTo("terms"));
        assertThat(terms.getBuckets().size(), equalTo(6));

        for (int i = 0; i < 6; i++) {
            Terms.Bucket bucket = terms.getBucketByKey("" + (i - 1d));
            assertThat(bucket, notNullValue());
            assertThat(key(bucket), equalTo("" + (i-1d)));
            assertThat(bucket.getKeyAsNumber().intValue(), equalTo(i-1));
            if (i == 0 || i == 5) {
                assertThat(bucket.getDocCount(), equalTo(1L));
            } else {
                assertThat(bucket.getDocCount(), equalTo(2L));
            }
        }
    }

    public void testMultiValuedFieldWithValueScriptNotUnique() throws Exception {
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(terms("terms")
                        .field(MULTI_VALUED_FIELD_NAME)
                        .collectMode(randomFrom(SubAggCollectionMode.values()))
                                .script(new Script("floor(_value / 1000 + 1)", ScriptType.INLINE, CustomScriptPlugin.NAME, null)))
                .get();

        assertSearchResponse(response);


        Terms terms = response.getAggregations().get("terms");
        assertThat(terms, notNullValue());
        assertThat(terms.getName(), equalTo("terms"));
        assertThat(terms.getBuckets().size(), equalTo(1));

        Terms.Bucket bucket = terms.getBucketByKey("1.0");
        assertThat(bucket, notNullValue());
        assertThat(key(bucket), equalTo("1.0"));
        assertThat(bucket.getKeyAsNumber().intValue(), equalTo(1));
        assertThat(bucket.getDocCount(), equalTo(5L));
    }

    /*

    [1, 2]
    [2, 3]
    [3, 4]
    [4, 5]
    [5, 6]

    1 - count: 1 - sum: 1
    2 - count: 2 - sum: 4
    3 - count: 2 - sum: 6
    4 - count: 2 - sum: 8
    5 - count: 2 - sum: 10
    6 - count: 1 - sum: 6

    */

    public void testScriptSingleValue() throws Exception {
        Script script = new Script("doc['" + SINGLE_VALUED_FIELD_NAME + "'].value", ScriptType.INLINE, CustomScriptPlugin.NAME, null);

        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(terms("terms")
                        .collectMode(randomFrom(SubAggCollectionMode.values()))
                        .script(script))
                .get();

        assertSearchResponse(response);


        Terms terms = response.getAggregations().get("terms");
        assertThat(terms, notNullValue());
        assertThat(terms.getName(), equalTo("terms"));
        assertThat(terms.getBuckets().size(), equalTo(5));

        for (int i = 0; i < 5; i++) {
            Terms.Bucket bucket = terms.getBucketByKey("" + i);
            assertThat(bucket, notNullValue());
            assertThat(key(bucket), equalTo("" + i));
            assertThat(bucket.getKeyAsNumber().intValue(), equalTo(i));
            assertThat(bucket.getDocCount(), equalTo(1L));
        }
    }

    public void testScriptMultiValued() throws Exception {
        Script script = new Script("doc['" + MULTI_VALUED_FIELD_NAME + "']", ScriptType.INLINE, CustomScriptPlugin.NAME, null);

        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(terms("terms")
                        .collectMode(randomFrom(SubAggCollectionMode.values()))
                        .script(script))
                .get();

        assertSearchResponse(response);


        Terms terms = response.getAggregations().get("terms");
        assertThat(terms, notNullValue());
        assertThat(terms.getName(), equalTo("terms"));
        assertThat(terms.getBuckets().size(), equalTo(6));

        for (int i = 0; i < 6; i++) {
            Terms.Bucket bucket = terms.getBucketByKey("" + i);
            assertThat(bucket, notNullValue());
            assertThat(key(bucket), equalTo("" + i));
            assertThat(bucket.getKeyAsNumber().intValue(), equalTo(i));
            if (i == 0 || i == 5) {
                assertThat(bucket.getDocCount(), equalTo(1L));
            } else {
                assertThat(bucket.getDocCount(), equalTo(2L));
            }
        }
    }

    public void testUnmapped() throws Exception {
        SearchResponse response = client().prepareSearch("idx_unmapped").setTypes("type")
                .addAggregation(terms("terms")
                        .field(SINGLE_VALUED_FIELD_NAME)
                        .size(randomIntBetween(1, 5))
                        .collectMode(randomFrom(SubAggCollectionMode.values())))
                .execute().actionGet();

        assertSearchResponse(response);


        Terms terms = response.getAggregations().get("terms");
        assertThat(terms, notNullValue());
        assertThat(terms.getName(), equalTo("terms"));
        assertThat(terms.getBuckets().size(), equalTo(0));
    }

    public void testPartiallyUnmapped() throws Exception {
        SearchResponse response = client().prepareSearch("idx_unmapped", "idx").setTypes("type")
                .addAggregation(terms("terms")
                        .field(SINGLE_VALUED_FIELD_NAME)
                        .collectMode(randomFrom(SubAggCollectionMode.values())))
                .execute().actionGet();

        assertSearchResponse(response);


        Terms terms = response.getAggregations().get("terms");
        assertThat(terms, notNullValue());
        assertThat(terms.getName(), equalTo("terms"));
        assertThat(terms.getBuckets().size(), equalTo(5));

        for (int i = 0; i < 5; i++) {
            Terms.Bucket bucket = terms.getBucketByKey("" + i);
            assertThat(bucket, notNullValue());
            assertThat(key(bucket), equalTo("" + i));
            assertThat(bucket.getKeyAsNumber().intValue(), equalTo(i));
            assertThat(bucket.getDocCount(), equalTo(1L));
        }
    }

    public void testPartiallyUnmappedWithFormat() throws Exception {
        SearchResponse response = client().prepareSearch("idx_unmapped", "idx").setTypes("type")
                .addAggregation(terms("terms")
                        .field(SINGLE_VALUED_FIELD_NAME)
                        .collectMode(randomFrom(SubAggCollectionMode.values()))
                        .format("0000"))
                .execute().actionGet();

        assertSearchResponse(response);


        Terms terms = response.getAggregations().get("terms");
        assertThat(terms, notNullValue());
        assertThat(terms.getName(), equalTo("terms"));
        assertThat(terms.getBuckets().size(), equalTo(5));

        for (int i = 0; i < 5; i++) {
            String key = String.format(Locale.ROOT, "%04d", i);
            Terms.Bucket bucket = terms.getBucketByKey(key);
            assertThat(bucket, notNullValue());
            assertThat(key(bucket), equalTo(key));
            assertThat(bucket.getKeyAsNumber().intValue(), equalTo(i));
            assertThat(bucket.getDocCount(), equalTo(1L));
        }
    }

    public void testEmptyAggregation() throws Exception {
        SearchResponse searchResponse = client().prepareSearch("empty_bucket_idx")
                .setQuery(matchAllQuery())
                .addAggregation(histogram("histo").field(SINGLE_VALUED_FIELD_NAME).interval(1L).minDocCount(0)
                        .subAggregation(terms("terms").field(SINGLE_VALUED_FIELD_NAME)))
                .execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(2L));
        Histogram histo = searchResponse.getAggregations().get("histo");
        assertThat(histo, Matchers.notNullValue());
        Histogram.Bucket bucket = histo.getBuckets().get(1);
        assertThat(bucket, Matchers.notNullValue());

        Terms terms = bucket.getAggregations().get("terms");
        assertThat(terms, Matchers.notNullValue());
        assertThat(terms.getName(), equalTo("terms"));
        assertThat(terms.getBuckets().isEmpty(), is(true));
    }

    public void testSingleValuedFieldOrderedBySingleValueSubAggregationAsc() throws Exception {
        boolean asc = true;
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(terms("terms")
                        .field(SINGLE_VALUED_FIELD_NAME)
                        .collectMode(randomFrom(SubAggCollectionMode.values()))
                        .order(Terms.Order.aggregation("avg_i", asc))
                        .subAggregation(avg("avg_i").field(SINGLE_VALUED_FIELD_NAME))
                ).execute().actionGet();


        assertSearchResponse(response);

        Terms terms = response.getAggregations().get("terms");
        assertThat(terms, notNullValue());
        assertThat(terms.getName(), equalTo("terms"));
        assertThat(terms.getBuckets().size(), equalTo(5));

        for (int i = 0; i < 5; i++) {
            Terms.Bucket bucket = terms.getBucketByKey("" + i);
            assertThat(bucket, notNullValue());
            assertThat(key(bucket), equalTo("" + i));
            assertThat(bucket.getDocCount(), equalTo(1L));
            Avg avg = bucket.getAggregations().get("avg_i");
            assertThat(avg, notNullValue());
            assertThat(avg.getValue(), equalTo((double) i));
        }
    }

    public void testSingleValuedFieldOrderedBySingleValueSubAggregationAscWithTermsSubAgg() throws Exception {
        boolean asc = true;
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(terms("terms")
                        .field(SINGLE_VALUED_FIELD_NAME)
                        .collectMode(randomFrom(SubAggCollectionMode.values()))
                        .order(Terms.Order.aggregation("avg_i", asc))
                        .subAggregation(
                                avg("avg_i").field(SINGLE_VALUED_FIELD_NAME))
                        .subAggregation(
                                terms("subTerms").field(MULTI_VALUED_FIELD_NAME).collectMode(randomFrom(SubAggCollectionMode.values())))
                ).get();


        assertSearchResponse(response);

        Terms terms = response.getAggregations().get("terms");
        assertThat(terms, notNullValue());
        assertThat(terms.getName(), equalTo("terms"));
        assertThat(terms.getBuckets().size(), equalTo(5));

        for (int i = 0; i < 5; i++) {
            Terms.Bucket bucket = terms.getBucketByKey("" + i);
            assertThat(bucket, notNullValue());
            assertThat(key(bucket), equalTo("" + i));
            assertThat(bucket.getDocCount(), equalTo(1L));

            Avg avg = bucket.getAggregations().get("avg_i");
            assertThat(avg, notNullValue());
            assertThat(avg.getValue(), equalTo((double) i));

            Terms subTermsAgg = bucket.getAggregations().get("subTerms");
            assertThat(subTermsAgg, notNullValue());
            assertThat(subTermsAgg.getBuckets().size(), equalTo(2));
            int j = i;
            for (Terms.Bucket subBucket : subTermsAgg.getBuckets()) {
                assertThat(subBucket, notNullValue());
                assertThat(key(subBucket), equalTo(String.valueOf(j)));
                assertThat(subBucket.getDocCount(), equalTo(1L));
                j++;
            }
        }
    }

    public void testSingleValuedFieldOrderedBySingleBucketSubAggregationAsc() throws Exception {
        boolean asc = randomBoolean();
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(terms("num_tags")
                        .field("num_tag")
                        .collectMode(randomFrom(SubAggCollectionMode.values()))
                        .order(Terms.Order.aggregation("filter", asc))
                        .subAggregation(filter("filter", QueryBuilders.matchAllQuery()))
                ).get();


        assertSearchResponse(response);

        Terms tags = response.getAggregations().get("num_tags");
        assertThat(tags, notNullValue());
        assertThat(tags.getName(), equalTo("num_tags"));
        assertThat(tags.getBuckets().size(), equalTo(2));

        Iterator<Terms.Bucket> iters = tags.getBuckets().iterator();

        Terms.Bucket tag = iters.next();
        assertThat(tag, notNullValue());
        assertThat(key(tag), equalTo(asc ? "0" : "1"));
        assertThat(tag.getDocCount(), equalTo(asc ? 2L : 3L));
        Filter filter = tag.getAggregations().get("filter");
        assertThat(filter, notNullValue());
        assertThat(filter.getDocCount(), equalTo(asc ? 2L : 3L));

        tag = iters.next();
        assertThat(tag, notNullValue());
        assertThat(key(tag), equalTo(asc ? "1" : "0"));
        assertThat(tag.getDocCount(), equalTo(asc ? 3L : 2L));
        filter = tag.getAggregations().get("filter");
        assertThat(filter, notNullValue());
        assertThat(filter.getDocCount(), equalTo(asc ? 3L : 2L));
    }

    public void testSingleValuedFieldOrderedBySubAggregationAscMultiHierarchyLevels() throws Exception {
        boolean asc = randomBoolean();
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(terms("tags")
                        .field("num_tag")
                        .collectMode(randomFrom(SubAggCollectionMode.values()))
                        .order(Terms.Order.aggregation("filter1>filter2>max", asc))
                .subAggregation(filter("filter1", QueryBuilders.matchAllQuery()).subAggregation(
                        filter("filter2", QueryBuilders.matchAllQuery())
                                        .subAggregation(max("max").field(SINGLE_VALUED_FIELD_NAME))))
                ).execute().actionGet();


        assertSearchResponse(response);

        Terms tags = response.getAggregations().get("tags");
        assertThat(tags, notNullValue());
        assertThat(tags.getName(), equalTo("tags"));
        assertThat(tags.getBuckets().size(), equalTo(2));

        Iterator<Terms.Bucket> iters = tags.getBuckets().iterator();

        // the max for "1" is 2
        // the max for "0" is 4

        Terms.Bucket tag = iters.next();
        assertThat(tag, notNullValue());
        assertThat(key(tag), equalTo(asc ? "1" : "0"));
        assertThat(tag.getDocCount(), equalTo(asc ? 3L : 2L));
        Filter filter1 = tag.getAggregations().get("filter1");
        assertThat(filter1, notNullValue());
        assertThat(filter1.getDocCount(), equalTo(asc ? 3L : 2L));
        Filter filter2 = filter1.getAggregations().get("filter2");
        assertThat(filter2, notNullValue());
        assertThat(filter2.getDocCount(), equalTo(asc ? 3L : 2L));
        Max max = filter2.getAggregations().get("max");
        assertThat(max, notNullValue());
        assertThat(max.getValue(), equalTo(asc ? 2.0 : 4.0));

        tag = iters.next();
        assertThat(tag, notNullValue());
        assertThat(key(tag), equalTo(asc ? "0" : "1"));
        assertThat(tag.getDocCount(), equalTo(asc ? 2L : 3L));
        filter1 = tag.getAggregations().get("filter1");
        assertThat(filter1, notNullValue());
        assertThat(filter1.getDocCount(), equalTo(asc ? 2L : 3L));
        filter2 = filter1.getAggregations().get("filter2");
        assertThat(filter2, notNullValue());
        assertThat(filter2.getDocCount(), equalTo(asc ? 2L : 3L));
        max = filter2.getAggregations().get("max");
        assertThat(max, notNullValue());
        assertThat(max.getValue(), equalTo(asc ? 4.0 : 2.0));
    }

    public void testSingleValuedFieldOrderedByMissingSubAggregation() throws Exception {
        for (String index : Arrays.asList("idx", "idx_unmapped")) {
            try {
                client().prepareSearch(index).setTypes("type")
                        .addAggregation(terms("terms")
                                .field(SINGLE_VALUED_FIELD_NAME)
                                .collectMode(randomFrom(SubAggCollectionMode.values()))
                                .order(Terms.Order.aggregation("avg_i", true))
                        ).execute().actionGet();

                fail("Expected search to fail when trying to sort terms aggregation by sug-aggregation that doesn't exist");

            } catch (ElasticsearchException e) {
                // expected
            }
        }
    }

    public void testSingleValuedFieldOrderedByNonMetricsOrMultiBucketSubAggregation() throws Exception {
        for (String index : Arrays.asList("idx", "idx_unmapped")) {
            try {
                client().prepareSearch(index).setTypes("type")
                        .addAggregation(terms("terms")
                                .field(SINGLE_VALUED_FIELD_NAME)
                                .collectMode(randomFrom(SubAggCollectionMode.values()))
                                .order(Terms.Order.aggregation("num_tags", true))
                                .subAggregation(terms("num_tags").field("num_tags")
                                        .collectMode(randomFrom(SubAggCollectionMode.values())))
                        ).execute().actionGet();

                fail("Expected search to fail when trying to sort terms aggregation by sug-aggregation which is not of a metrics type");

            } catch (ElasticsearchException e) {
                // expected
            }
        }
    }

    public void testSingleValuedFieldOrderedByMultiValuedSubAggregationWithUknownMetric() throws Exception {
        for (String index : Arrays.asList("idx", "idx_unmapped")) {
            try {
                client().prepareSearch(index).setTypes("type")
                        .addAggregation(terms("terms")
                                .field(SINGLE_VALUED_FIELD_NAME)
                                .collectMode(randomFrom(SubAggCollectionMode.values()))
                                .order(Terms.Order.aggregation("stats.foo", true))
                                .subAggregation(stats("stats").field(SINGLE_VALUED_FIELD_NAME))
                        ).execute().actionGet();

                fail("Expected search to fail when trying to sort terms aggregation by multi-valued sug-aggregation " +
                        "with an unknown specified metric to order by");

            } catch (ElasticsearchException e) {
                // expected
            }
        }
    }

    public void testSingleValuedFieldOrderedByMultiValuedSubAggregationWithoutMetric() throws Exception {
        for (String index : Arrays.asList("idx", "idx_unmapped")) {
            try {
                client().prepareSearch(index).setTypes("type")
                        .addAggregation(terms("terms")
                                .field(SINGLE_VALUED_FIELD_NAME)
                                .collectMode(randomFrom(SubAggCollectionMode.values()))
                                .order(Terms.Order.aggregation("stats", true))
                                .subAggregation(stats("stats").field(SINGLE_VALUED_FIELD_NAME))
                        ).execute().actionGet();

                fail("Expected search to fail when trying to sort terms aggregation by multi-valued sug-aggregation " +
                        "where the metric name is not specified");

            } catch (ElasticsearchException e) {
                // expected
            }
        }
    }

    public void testSingleValuedFieldOrderedBySingleValueSubAggregationDesc() throws Exception {
        boolean asc = false;
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(terms("terms")
                        .field(SINGLE_VALUED_FIELD_NAME)
                        .collectMode(randomFrom(SubAggCollectionMode.values()))
                        .order(Terms.Order.aggregation("avg_i", asc))
                        .subAggregation(avg("avg_i").field(SINGLE_VALUED_FIELD_NAME))
                ).execute().actionGet();


        assertSearchResponse(response);

        Terms terms = response.getAggregations().get("terms");
        assertThat(terms, notNullValue());
        assertThat(terms.getName(), equalTo("terms"));
        assertThat(terms.getBuckets().size(), equalTo(5));

        for (int i = 4; i >= 0; i--) {

            Terms.Bucket bucket = terms.getBucketByKey("" + i);
            assertThat(bucket, notNullValue());
            assertThat(key(bucket), equalTo("" + i));
            assertThat(bucket.getDocCount(), equalTo(1L));

            Avg avg = bucket.getAggregations().get("avg_i");
            assertThat(avg, notNullValue());
            assertThat(avg.getValue(), equalTo((double) i));
        }

    }

    public void testSingleValuedFieldOrderedByMultiValueSubAggregationAsc() throws Exception {
        boolean asc = true;
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(terms("terms")
                        .field(SINGLE_VALUED_FIELD_NAME)
                        .collectMode(randomFrom(SubAggCollectionMode.values()))
                        .order(Terms.Order.aggregation("stats.avg", asc))
                        .subAggregation(stats("stats").field(SINGLE_VALUED_FIELD_NAME))
                ).execute().actionGet();

        assertSearchResponse(response);

        Terms terms = response.getAggregations().get("terms");
        assertThat(terms, notNullValue());
        assertThat(terms.getName(), equalTo("terms"));
        assertThat(terms.getBuckets().size(), equalTo(5));

        for (int i = 0; i < 5; i++) {
            Terms.Bucket bucket = terms.getBucketByKey("" + i);
            assertThat(bucket, notNullValue());
            assertThat(key(bucket), equalTo("" + i));
            assertThat(bucket.getDocCount(), equalTo(1L));

            Stats stats = bucket.getAggregations().get("stats");
            assertThat(stats, notNullValue());
            assertThat(stats.getMax(), equalTo((double) i));
        }

    }

    public void testSingleValuedFieldOrderedByMultiValueSubAggregationDesc() throws Exception {
        boolean asc = false;
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(terms("terms")
                        .field(SINGLE_VALUED_FIELD_NAME)
                        .collectMode(randomFrom(SubAggCollectionMode.values()))
                        .order(Terms.Order.aggregation("stats.avg", asc))
                        .subAggregation(stats("stats").field(SINGLE_VALUED_FIELD_NAME))
                ).execute().actionGet();

        assertSearchResponse(response);

        Terms terms = response.getAggregations().get("terms");
        assertThat(terms, notNullValue());
        assertThat(terms.getName(), equalTo("terms"));
        assertThat(terms.getBuckets().size(), equalTo(5));

        for (int i = 4; i >= 0; i--) {
            Terms.Bucket bucket = terms.getBucketByKey("" + i);
            assertThat(bucket, notNullValue());
            assertThat(key(bucket), equalTo("" + i));
            assertThat(bucket.getDocCount(), equalTo(1L));

            Stats stats = bucket.getAggregations().get("stats");
            assertThat(stats, notNullValue());
            assertThat(stats.getMax(), equalTo((double) i));
        }

    }

    public void testSingleValuedFieldOrderedByMultiValueExtendedStatsAsc() throws Exception {
        boolean asc = true;
        SearchResponse response = client().prepareSearch("idx").setTypes("type")
                .addAggregation(terms("terms")
                        .field(SINGLE_VALUED_FIELD_NAME)
                        .collectMode(randomFrom(SubAggCollectionMode.values()))
                        .order(Terms.Order.aggregation("stats.variance", asc))
                        .subAggregation(extendedStats("stats").field(SINGLE_VALUED_FIELD_NAME))
                ).execute().actionGet();

        assertSearchResponse(response);

        Terms terms = response.getAggregations().get("terms");
        assertThat(terms, notNullValue());
        assertThat(terms.getName(), equalTo("terms"));
        assertThat(terms.getBuckets().size(), equalTo(5));

        for (int i = 0; i < 5; i++) {
            Terms.Bucket bucket = terms.getBucketByKey("" + i);
            assertThat(bucket, notNullValue());
            assertThat(key(bucket), equalTo("" + i));
            assertThat(bucket.getDocCount(), equalTo(1L));

            ExtendedStats stats = bucket.getAggregations().get("stats");
            assertThat(stats, notNullValue());
            assertThat(stats.getMax(), equalTo((double) i));
        }

    }

    public void testSingleValuedFieldOrderedBySingleValueSubAggregationAscAndTermsDesc() throws Exception {
        long[] expectedKeys = new long[] { 1, 2, 4, 3, 7, 6, 5 };
        assertMultiSortResponse(expectedKeys, Terms.Order.aggregation("avg_l", true), Terms.Order.term(false));
    }

    public void testSingleValuedFieldOrderedBySingleValueSubAggregationAscAndTermsAsc() throws Exception {
        long[] expectedKeys = new long[] { 1, 2, 3, 4, 5, 6, 7 };
        assertMultiSortResponse(expectedKeys, Terms.Order.aggregation("avg_l", true), Terms.Order.term(true));
    }

    public void testSingleValuedFieldOrderedBySingleValueSubAggregationDescAndTermsAsc() throws Exception {
        long[] expectedKeys = new long[] { 5, 6, 7, 3, 4, 2, 1 };
        assertMultiSortResponse(expectedKeys, Terms.Order.aggregation("avg_l", false), Terms.Order.term(true));
    }

    public void testSingleValuedFieldOrderedByCountAscAndSingleValueSubAggregationAsc() throws Exception {
        long[] expectedKeys = new long[] { 6, 7, 3, 4, 5, 1, 2 };
        assertMultiSortResponse(expectedKeys, Terms.Order.count(true), Terms.Order.aggregation("avg_l", true));
    }

    public void testSingleValuedFieldOrderedBySingleValueSubAggregationAscSingleValueSubAggregationAsc() throws Exception {
        long[] expectedKeys = new long[] { 6, 7, 3, 5, 4, 1, 2 };
        assertMultiSortResponse(expectedKeys, Terms.Order.aggregation("sum_d", true), Terms.Order.aggregation("avg_l", true));
    }

    public void testSingleValuedFieldOrderedByThreeCriteria() throws Exception {
        long[] expectedKeys = new long[] { 2, 1, 4, 5, 3, 6, 7 };
        assertMultiSortResponse(expectedKeys, Terms.Order.count(false),
                Terms.Order.aggregation("sum_d", false),
                Terms.Order.aggregation("avg_l", false));
    }

    public void testSingleValuedFieldOrderedBySingleValueSubAggregationAscAsCompound() throws Exception {
        long[] expectedKeys = new long[] { 1, 2, 3, 4, 5, 6, 7 };
        assertMultiSortResponse(expectedKeys, Terms.Order.aggregation("avg_l", true));
    }

    private void assertMultiSortResponse(long[] expectedKeys, Terms.Order... order) {
        SearchResponse response = client().prepareSearch("sort_idx").setTypes("multi_sort_type")
                .addAggregation(terms("terms")
                        .field(SINGLE_VALUED_FIELD_NAME)
                        .collectMode(randomFrom(SubAggCollectionMode.values()))
                        .order(Terms.Order.compound(order))
                        .subAggregation(avg("avg_l").field("l"))
                        .subAggregation(sum("sum_d").field("d"))
                ).execute().actionGet();

        assertSearchResponse(response);

        Terms terms = response.getAggregations().get("terms");
        assertThat(terms, notNullValue());
        assertThat(terms.getName(), equalTo("terms"));
        assertThat(terms.getBuckets().size(), equalTo(expectedKeys.length));

        int i = 0;
        for (Terms.Bucket bucket : terms.getBuckets()) {
            assertThat(bucket, notNullValue());
            assertThat(key(bucket), equalTo(String.valueOf(expectedKeys[i])));
            assertThat(bucket.getDocCount(), equalTo(expectedMultiSortBuckets.get(expectedKeys[i]).get("_count")));
            Avg avg = bucket.getAggregations().get("avg_l");
            assertThat(avg, notNullValue());
            assertThat(avg.getValue(), equalTo(expectedMultiSortBuckets.get(expectedKeys[i]).get("avg_l")));
            Sum sum = bucket.getAggregations().get("sum_d");
            assertThat(sum, notNullValue());
            assertThat(sum.getValue(), equalTo(expectedMultiSortBuckets.get(expectedKeys[i]).get("sum_d")));
            i++;
        }
    }

    public void testOtherDocCount() {
        testOtherDocCount(SINGLE_VALUED_FIELD_NAME, MULTI_VALUED_FIELD_NAME);
    }

    /**
     * Make sure that a request using a script does not get cached and a request
     * not using a script does get cached.
     */
    public void testDontCacheScripts() throws Exception {
        assertAcked(prepareCreate("cache_test_idx").addMapping("type", "d", "type=long")
                .setSettings(Settings.builder().put("requests.cache.enable", true).put("number_of_shards", 1).put("number_of_replicas", 1))
                .get());
        indexRandom(true, client().prepareIndex("cache_test_idx", "type", "1").setSource("s", 1),
                client().prepareIndex("cache_test_idx", "type", "2").setSource("s", 2));

        // Make sure we are starting with a clear cache
        assertThat(client().admin().indices().prepareStats("cache_test_idx").setRequestCache(true).get().getTotal().getRequestCache()
                .getHitCount(), equalTo(0L));
        assertThat(client().admin().indices().prepareStats("cache_test_idx").setRequestCache(true).get().getTotal().getRequestCache()
                .getMissCount(), equalTo(0L));

        // Test that a request using a script does not get cached
        SearchResponse r = client().prepareSearch("cache_test_idx").setSize(0).addAggregation(
                terms("terms").field("d").script(new Script("_value + 1", ScriptType.INLINE, CustomScriptPlugin.NAME, null))).get();
        assertSearchResponse(r);

        assertThat(client().admin().indices().prepareStats("cache_test_idx").setRequestCache(true).get().getTotal().getRequestCache()
                .getHitCount(), equalTo(0L));
        assertThat(client().admin().indices().prepareStats("cache_test_idx").setRequestCache(true).get().getTotal().getRequestCache()
                .getMissCount(), equalTo(0L));

        // To make sure that the cache is working test that a request not using
        // a script is cached
        r = client().prepareSearch("cache_test_idx").setSize(0).addAggregation(terms("terms").field("d")).get();
        assertSearchResponse(r);

        assertThat(client().admin().indices().prepareStats("cache_test_idx").setRequestCache(true).get().getTotal().getRequestCache()
                .getHitCount(), equalTo(0L));
        assertThat(client().admin().indices().prepareStats("cache_test_idx").setRequestCache(true).get().getTotal().getRequestCache()
                .getMissCount(), equalTo(1L));
    }
}