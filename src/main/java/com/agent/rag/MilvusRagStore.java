package com.agent.rag;

import com.agent.embedding.EmbeddingModel;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.SearchResults;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.FlushParam;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.SearchResultsWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 默认 RAG 实现：Milvus。id 为主键 VarChar，text 为标量字段，vector 由
 * {@link EmbeddingModel} 生成；hybridSearch 在向量召回之上做关键词加权合并。
 * 依赖以 optional 引入，宿主未引入 milvus-sdk 时本类不会被装配。
 */
public final class MilvusRagStore implements RagStore, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MilvusRagStore.class);

    private static final String ID_FIELD = "id";
    private static final String TEXT_FIELD = "text";
    private static final String VECTOR_FIELD = "vector";

    private final MilvusClientPool pool;
    private final EmbeddingModel embedding;
    private final String collection;
    private final AtomicInteger unflushed = new AtomicInteger();

    public MilvusRagStore(RagConfig config, EmbeddingModel embedding) {
        this.collection = config.collection();
        this.embedding = embedding;
        this.pool = new MilvusClientPool(ConnectParam.newBuilder()
                .withHost(config.host())
                .withPort(config.port())
                .build(), config.poolMaxSize(), config.poolAcquireTimeoutMs());
        MilvusServiceClient client = pool.borrow();
        try {
            ensureCollection(client, embedding.dimensions());
        } finally {
            pool.release(client);
        }
        log.info("MilvusRagStore 已连接 {}:{} collection={} 池大小={}",
                config.host(), config.port(), collection, pool.maxSize());
    }

    private void ensureCollection(MilvusServiceClient client, int dim) {
        R<Boolean> has = client.hasCollection(
                HasCollectionParam.newBuilder().withCollectionName(collection).build());
        if (!Boolean.TRUE.equals(has.getData())) {
            FieldType idField = FieldType.newBuilder()
                    .withName(ID_FIELD)
                    .withDataType(DataType.VarChar)
                    .withMaxLength(128)
                    .withPrimaryKey(true)
                    .withAutoID(false)
                    .build();
            FieldType textField = FieldType.newBuilder()
                    .withName(TEXT_FIELD)
                    .withDataType(DataType.VarChar)
                    .withMaxLength(65535)
                    .build();
            FieldType vectorField = FieldType.newBuilder()
                    .withName(VECTOR_FIELD)
                    .withDataType(DataType.FloatVector)
                    .withDimension(dim)
                    .build();
            client.createCollection(CreateCollectionParam.newBuilder()
                    .withCollectionName(collection)
                    .withShardsNum(1)
                    .addFieldType(idField)
                    .addFieldType(textField)
                    .addFieldType(vectorField)
                    .build());
            client.createIndex(CreateIndexParam.newBuilder()
                    .withCollectionName(collection)
                    .withFieldName(VECTOR_FIELD)
                    .withIndexType(IndexType.IVF_FLAT)
                    .withMetricType(MetricType.COSINE)
                    .withExtraParam("{\"nlist\":128}")
                    .build());
        }
        client.loadCollection(LoadCollectionParam.newBuilder().withCollectionName(collection).build());
    }

    @Override
    public void index(String id, String text) {
        MilvusServiceClient client = pool.borrow();
        try {
        client.insert(InsertParam.newBuilder()
                .withCollectionName(collection)
                .withFields(List.of(
                        new InsertParam.Field(ID_FIELD, List.of(id)),
                        new InsertParam.Field(TEXT_FIELD, List.of(text)),
                        new InsertParam.Field(VECTOR_FIELD, List.of(toFloats(embedding.embed(text))))))
                .build());
        if (unflushed.incrementAndGet() % 32 == 0) {
            flush();
        }
        } finally {
            pool.release(client);
        }
    }

    public void flush() {
        MilvusServiceClient client = pool.borrow();
        try {
            client.flush(FlushParam.newBuilder()
                    .withCollectionNames(List.of(collection))
                    .withSyncFlush(false)
                    .build());
        } finally {
            pool.release(client);
        }
    }

    @Override
    public List<RagHit> search(String query, int topK) {
        List<Float> vector = toFloats(embedding.embed(query));
        MilvusServiceClient client = pool.borrow();
        R<SearchResults> resp;
        try {
            resp = client.search(SearchParam.newBuilder()
                    .withCollectionName(collection)
                    .withMetricType(MetricType.COSINE)
                    .withOutFields(List.of(TEXT_FIELD))
                    .withFloatVectors(List.of(vector))
                    .withTopK(Math.max(1, topK))
                    .withParams("{\"nprobe\":16}")
                    .build());
        } finally {
            pool.release(client);
        }
        return toHits(resp, topK);
    }

    @Override
    public List<RagHit> hybridSearch(String query, int topK) {
        // 向量召回 topK*2，再按关键词命中率加权合并
        List<RagHit> candidates = search(query, Math.max(topK, 2) * 2);
        List<String> tokens = tokenize(query);
        List<RagHit> merged = new ArrayList<>();
        for (RagHit hit : candidates) {
            double boost = 0;
            for (String token : tokens) {
                if (hit.text() != null && hit.text().toLowerCase().contains(token)) {
                    boost += 0.1;
                }
            }
            merged.add(new RagHit(hit.id(), hit.text(), hit.score() + boost));
        }
        merged.sort((a, b) -> Double.compare(b.score(), a.score()));
        return merged.size() > topK ? new ArrayList<>(merged.subList(0, topK)) : merged;
    }

    private List<RagHit> toHits(R<SearchResults> resp, int topK) {
        if (resp == null || resp.getStatus() == null
                || resp.getStatus() != R.Status.Success.getCode()) {
            log.warn("Milvus 检索失败: {}", resp == null ? "null" : resp.getMessage());
            return Collections.emptyList();
        }
        List<RagHit> hits = new ArrayList<>();
        try {
            SearchResultsWrapper wrapper = new SearchResultsWrapper(resp.getData().getResults());
            for (io.milvus.response.QueryResultsWrapper.RowRecord row : wrapper.getRowRecords(0)) {
                Object id = row.get(ID_FIELD);
                Object text = row.get(TEXT_FIELD);
                Object dist = row.get("distance");
                hits.add(new RagHit(String.valueOf(id), text == null ? "" : String.valueOf(text),
                        dist instanceof Number n ? n.doubleValue() : 0));
                if (hits.size() >= topK) {
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("Milvus 检索结果解析失败: {}", e.getMessage());
        }
        return hits;
    }

    private static List<Float> toFloats(float[] vector) {
        List<Float> floats = new ArrayList<>(vector.length);
        for (float v : vector) {
            floats.add(v);
        }
        return floats;
    }

    private static List<String> tokenize(String query) {
        String[] parts = query.toLowerCase().split("[\\s,，。.;；:：!?！？]+");
        List<String> tokens = new ArrayList<>();
        for (String p : parts) {
            if (p.length() >= 2) {
                tokens.add(p);
            }
        }
        return tokens;
    }

    @Override
    public void close() {
        pool.close();
    }
}
