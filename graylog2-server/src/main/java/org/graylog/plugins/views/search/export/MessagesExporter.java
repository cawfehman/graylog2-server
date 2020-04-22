/**
 * This file is part of Graylog.
 *
 * Graylog is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Graylog is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Graylog.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.graylog.plugins.views.search.export;

import org.graylog.plugins.views.search.Query;
import org.graylog.plugins.views.search.Search;
import org.graylog.plugins.views.search.SearchType;
import org.graylog.plugins.views.search.elasticsearch.ElasticsearchQueryString;
import org.graylog.plugins.views.search.searchtypes.MessageList;

import javax.inject.Inject;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

public class MessagesExporter {
    private final ExportBackend backend;
    private final ChunkDecorator chunkDecorator;
    private final QueryStringDecorator queryStringDecorator;

    @Inject
    public MessagesExporter(ExportBackend backend, ChunkDecorator chunkDecorator, QueryStringDecorator queryStringDecorator) {
        this.backend = backend;
        this.chunkDecorator = chunkDecorator;
        this.queryStringDecorator = queryStringDecorator;
    }

    public void export(MessagesRequest request, Consumer<SimpleMessageChunk> chunkForwarder) {
        backend.run(request, chunkForwarder);
    }

    public void export(Search search, ResultFormat resultFormat, Consumer<SimpleMessageChunk> chunkForwarder) {
        MessagesRequest request = buildRequest(search, null, resultFormat);

        export(request, chunkForwarder);
    }

    public void export(Search search, String searchTypeId, ResultFormat resultFormat, Consumer<SimpleMessageChunk> chunkForwarder) {
        MessagesRequest request = buildRequest(search, searchTypeId, resultFormat);

        Consumer<SimpleMessageChunk> decoratedForwarder = decorateIfNecessary(search, searchTypeId, chunkForwarder, request);

        export(request, decoratedForwarder);
    }

    private Consumer<SimpleMessageChunk> decorateIfNecessary(Search search, String searchTypeId, Consumer<SimpleMessageChunk> chunkForwarder, MessagesRequest request) {
        Query query = queryFrom(search, searchTypeId);
        Optional<MessageList> messageList = messageListFrom(query, searchTypeId);

        return messageList.isPresent()
                ? chunk -> decorate(chunkForwarder, messageList.get(), chunk, request)
                : chunkForwarder;
    }

    private void decorate(Consumer<SimpleMessageChunk> chunkForwarder, MessageList messageList, SimpleMessageChunk chunk, MessagesRequest request) {
        SimpleMessageChunk decoratedChunk = chunkDecorator.decorate(chunk, messageList.decorators(), request);

        chunkForwarder.accept(decoratedChunk);
    }

    private MessagesRequest buildRequest(Search search, String searchTypeId, ResultFormat resultFormat) {
        Query query = queryFrom(search, searchTypeId);

        MessagesRequest.Builder requestBuilder = MessagesRequest.builder();

        setTimeRange(query, searchTypeId, requestBuilder);
        trySetQueryString(search, searchTypeId, requestBuilder);
        setStreams(query, searchTypeId, requestBuilder);
        setFields(resultFormat, requestBuilder);
        trySetSort(query, searchTypeId, resultFormat, requestBuilder);
        trySetLimit(resultFormat, requestBuilder);

        return requestBuilder.build();
    }

    private Query queryFrom(Search s, String searchTypeId) {
        if (searchTypeId != null) {
            return s.queryForSearchType(searchTypeId);
        }

        if (s.queries().size() > 1) {
            throw new ExportException("Can't get messages for search with id " + s.id() + ", because it contains multiple queries");
        }

        return s.queries().stream().findFirst()
                .orElseThrow(() -> new ExportException("Invalid Search object with empty Query"));
    }

    private void setTimeRange(Query query, String searchTypeId, MessagesRequest.Builder requestBuilder) {
        Optional<MessageList> ml = messageListFrom(query, searchTypeId);
        if (ml.isPresent() && ml.get().timerange().isPresent()) {
            requestBuilder.timeRange(query.effectiveTimeRange(ml.get()));
        } else {
            requestBuilder.timeRange(query.timerange());
        }
    }

    private void trySetQueryString(Search search, String searchTypeId, MessagesRequest.Builder requestBuilder) {
        Query query = queryFrom(search, searchTypeId);

        ElasticsearchQueryString undecorated = pickQueryString(searchTypeId, query);

        ElasticsearchQueryString decorated = decorateQueryString(search, query, undecorated);

        requestBuilder.queryString(decorated);
    }

    private ElasticsearchQueryString pickQueryString(String searchTypeId, Query query) {
        Optional<MessageList> ml = messageListFrom(query, searchTypeId);
        boolean messageListHasQueryString = ml.isPresent() && ml.get().query().isPresent();
        boolean queryHasQueryString = query.query() instanceof ElasticsearchQueryString;

        if (messageListHasQueryString && queryHasQueryString) {
            return esQueryStringFrom(query).concatenate(esQueryStringFrom(ml.get()));
        } else if (queryHasQueryString) {
            return esQueryStringFrom(query);
        } else if (messageListHasQueryString) {
            return esQueryStringFrom(ml.get());
        }
        return ElasticsearchQueryString.empty();
    }

    private ElasticsearchQueryString esQueryStringFrom(MessageList ml) {
        //noinspection OptionalGetWithoutIsPresent
        return (ElasticsearchQueryString) ml.query().get();
    }

    private ElasticsearchQueryString esQueryStringFrom(Query query) {
        return (ElasticsearchQueryString) query.query();
    }

    private ElasticsearchQueryString decorateQueryString(Search search, Query query, ElasticsearchQueryString undecorated) {
        String queryString = undecorated.queryString();

        String decorated = queryStringDecorator.decorateQueryString(queryString, search, query);

        return ElasticsearchQueryString.builder().queryString(decorated).build();
    }

    private void setStreams(Query query, String searchTypeId, MessagesRequest.Builder requestBuilder) {
        Optional<MessageList> messageList = messageListFrom(query, searchTypeId);
        if (messageList.isPresent()) {
            Set<String> streams = messageList.get().effectiveStreams().isEmpty() ?
                    query.usedStreamIds() :
                    messageList.get().effectiveStreams();
            requestBuilder.streams(streams);
        } else {
            requestBuilder.streams(query.usedStreamIds());
        }
    }

    private void setFields(ResultFormat resultFormat, MessagesRequest.Builder requestBuilder) {
        requestBuilder.fieldsInOrder(resultFormat.fieldsInOrder());
    }

    private void trySetSort(Query query, String searchTypeId, ResultFormat resultFormat, MessagesRequest.Builder requestBuilder) {
        Optional<MessageList> ml = messageListFrom(query, searchTypeId);
        if (!resultFormat.sort().isEmpty()) {
            requestBuilder.sort(resultFormat.sort());
        } else if (ml.isPresent() && ml.get().sort() != null) {
            requestBuilder.sort(new LinkedHashSet<>(ml.get().sort()));
        }
    }

    private void trySetLimit(ResultFormat resultFormat, MessagesRequest.Builder requestBuilder) {
        resultFormat.limit().ifPresent(requestBuilder::limit);
    }

    private Optional<MessageList> messageListFrom(Query query, String searchTypeId) {
        Optional<SearchType> searchType = searchTypeFrom(query, searchTypeId);

        if (!searchType.isPresent()) {
            return Optional.empty();
        }
        return searchType.map(st -> {
            if (!(st instanceof MessageList)) {
                throw new ExportException("Only message lists are currently supported");
            }
            return (MessageList) st;
        });
    }

    private Optional<SearchType> searchTypeFrom(Query query, String searchTypeId) {
        return query.searchTypes().stream()
                .filter(st -> st.id().equals(searchTypeId))
                .findFirst();
    }
}