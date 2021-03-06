package graphql.servlet;

import com.google.common.io.ByteStreams;
import com.google.common.io.CharStreams;
import graphql.ExecutionResult;
import graphql.introspection.IntrospectionQuery;
import graphql.schema.GraphQLFieldDefinition;
import graphql.servlet.internal.GraphQLRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.AsyncContext;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;
import java.io.*;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author Andrew Potter
 */
public abstract class AbstractGraphQLHttpServlet extends HttpServlet implements Servlet, GraphQLMBean {

    public static final Logger log = LoggerFactory.getLogger(AbstractGraphQLHttpServlet.class);

    public static final String APPLICATION_JSON_UTF8 = "application/json;charset=UTF-8";
    public static final String APPLICATION_GRAPHQL = "application/graphql";
    public static final int STATUS_OK = 200;
    public static final int STATUS_BAD_REQUEST = 400;

    private static final GraphQLRequest INTROSPECTION_REQUEST = new GraphQLRequest(IntrospectionQuery.INTROSPECTION_QUERY, new HashMap<>(), null);

    protected abstract GraphQLQueryInvoker getQueryInvoker();

    protected abstract GraphQLInvocationInputFactory getInvocationInputFactory();

    protected abstract GraphQLObjectMapper getGraphQLObjectMapper();

    private final List<GraphQLServletListener> listeners;

    private final HttpRequestHandler getHandler;
    private final HttpRequestHandler postHandler;

    private final boolean asyncServletMode;

    public AbstractGraphQLHttpServlet() {
        this(null, false);
    }

    public AbstractGraphQLHttpServlet(List<GraphQLServletListener> listeners, boolean asyncServletMode) {
        this.listeners = listeners != null ? new ArrayList<>(listeners) : new ArrayList<>();
        this.asyncServletMode = asyncServletMode;

        this.getHandler = (request, response) -> {
            GraphQLInvocationInputFactory invocationInputFactory = getInvocationInputFactory();
            GraphQLObjectMapper graphQLObjectMapper = getGraphQLObjectMapper();
            GraphQLQueryInvoker queryInvoker = getQueryInvoker();

            String path = request.getPathInfo();
            if (path == null) {
                path = request.getServletPath();
            }
            if (path.contentEquals("/schema.json")) {
                query(queryInvoker, graphQLObjectMapper, invocationInputFactory.create(INTROSPECTION_REQUEST, request), response);
            } else {
                String query = request.getParameter("query");
                if (query != null) {

                    if (isBatchedQuery(query)) {
                        queryBatched(queryInvoker, graphQLObjectMapper, invocationInputFactory.createReadOnly(graphQLObjectMapper.readBatchedGraphQLRequest(query), request), response);
                    } else {
                        final Map<String, Object> variables = new HashMap<>();
                        if (request.getParameter("variables") != null) {
                            variables.putAll(graphQLObjectMapper.deserializeVariables(request.getParameter("variables")));
                        }

                        String operationName = request.getParameter("operationName");

                        query(queryInvoker, graphQLObjectMapper, invocationInputFactory.createReadOnly(new GraphQLRequest(query, variables, operationName), request), response);
                    }
                } else {
                    response.setStatus(STATUS_BAD_REQUEST);
                    log.info("Bad GET request: path was not \"/schema.json\" or no query variable named \"query\" given");
                }
            }
        };

        this.postHandler = (request, response) -> {
            GraphQLInvocationInputFactory invocationInputFactory = getInvocationInputFactory();
            GraphQLObjectMapper graphQLObjectMapper = getGraphQLObjectMapper();
            GraphQLQueryInvoker queryInvoker = getQueryInvoker();

            try {
                if (APPLICATION_GRAPHQL.equals(request.getContentType())) {
                    String query = CharStreams.toString(request.getReader());
                    query(queryInvoker, graphQLObjectMapper, invocationInputFactory.create(new GraphQLRequest(query, null, null)), response);
                } else if (request.getContentType() != null && request.getContentType().startsWith("multipart/form-data") && !request.getParts().isEmpty()) {
                    final Map<String, List<Part>> fileItems = request.getParts().stream()
                            .collect(Collectors.toMap(
                                    Part::getName,
                                    Collections::singletonList,
                                    (l1, l2) -> Stream.concat(l1.stream(), l2.stream()).collect(Collectors.toList())));

                    if (fileItems.containsKey("graphql")) {
                        final Optional<Part> graphqlItem = getFileItem(fileItems, "graphql");
                        if (graphqlItem.isPresent()) {
                            InputStream inputStream = graphqlItem.get().getInputStream();

                            if (!inputStream.markSupported()) {
                                inputStream = new BufferedInputStream(inputStream);
                            }

                            if (isBatchedQuery(inputStream)) {
                                GraphQLBatchedInvocationInput invocationInput = invocationInputFactory.create(graphQLObjectMapper.readBatchedGraphQLRequest(inputStream), request);
                                invocationInput.getContext().setFiles(fileItems);
                                queryBatched(queryInvoker, graphQLObjectMapper, invocationInput, response);
                                return;
                            } else {
                                GraphQLSingleInvocationInput invocationInput = invocationInputFactory.create(graphQLObjectMapper.readGraphQLRequest(inputStream), request);
                                invocationInput.getContext().setFiles(fileItems);
                                query(queryInvoker, graphQLObjectMapper, invocationInput, response);
                                return;
                            }
                        }
                    } else if (fileItems.containsKey("query")) {
                        final Optional<Part> queryItem = getFileItem(fileItems, "query");
                        if (queryItem.isPresent()) {
                            InputStream inputStream = queryItem.get().getInputStream();

                            if (!inputStream.markSupported()) {
                                inputStream = new BufferedInputStream(inputStream);
                            }

                            if (isBatchedQuery(inputStream)) {
                                GraphQLBatchedInvocationInput invocationInput = invocationInputFactory.create(graphQLObjectMapper.readBatchedGraphQLRequest(inputStream), request);
                                invocationInput.getContext().setFiles(fileItems);
                                queryBatched(queryInvoker, graphQLObjectMapper, invocationInput, response);
                                return;
                            } else {
                                String query = new String(ByteStreams.toByteArray(inputStream));

                                Map<String, Object> variables = null;
                                final Optional<Part> variablesItem = getFileItem(fileItems, "variables");
                                if (variablesItem.isPresent()) {
                                    variables = graphQLObjectMapper.deserializeVariables(new String(ByteStreams.toByteArray(variablesItem.get().getInputStream())));
                                }

                                String operationName = null;
                                final Optional<Part> operationNameItem = getFileItem(fileItems, "operationName");
                                if (operationNameItem.isPresent()) {
                                    operationName = new String(ByteStreams.toByteArray(operationNameItem.get().getInputStream())).trim();
                                }

                                GraphQLSingleInvocationInput invocationInput = invocationInputFactory.create(new GraphQLRequest(query, variables, operationName), request);
                                invocationInput.getContext().setFiles(fileItems);
                                query(queryInvoker, graphQLObjectMapper, invocationInput, response);
                                return;
                            }
                        }
                    }

                    response.setStatus(STATUS_BAD_REQUEST);
                    log.info("Bad POST multipart request: no part named \"graphql\" or \"query\"");
                } else {
                    // this is not a multipart request
                    InputStream inputStream = request.getInputStream();

                    if (!inputStream.markSupported()) {
                        inputStream = new BufferedInputStream(inputStream);
                    }

                    if (isBatchedQuery(inputStream)) {
                        queryBatched(queryInvoker, graphQLObjectMapper, invocationInputFactory.create(graphQLObjectMapper.readBatchedGraphQLRequest(inputStream), request), response);
                    } else {
                        query(queryInvoker, graphQLObjectMapper, invocationInputFactory.create(graphQLObjectMapper.readGraphQLRequest(inputStream), request), response);
                    }
                }
            } catch (Exception e) {
                log.info("Bad POST request: parsing failed", e);
                response.setStatus(STATUS_BAD_REQUEST);
            }
        };
    }

    public void addListener(GraphQLServletListener servletListener) {
        listeners.add(servletListener);
    }

    public void removeListener(GraphQLServletListener servletListener) {
        listeners.remove(servletListener);
    }

    @Override
    public String[] getQueries() {
        return getInvocationInputFactory().getSchemaProvider().getSchema().getQueryType().getFieldDefinitions().stream().map(GraphQLFieldDefinition::getName).toArray(String[]::new);
    }

    @Override
    public String[] getMutations() {
        return getInvocationInputFactory().getSchemaProvider().getSchema().getMutationType().getFieldDefinitions().stream().map(GraphQLFieldDefinition::getName).toArray(String[]::new);
    }

    @Override
    public String executeQuery(String query) {
        try {
            return getGraphQLObjectMapper().serializeResultAsJson(getQueryInvoker().query(getInvocationInputFactory().create(new GraphQLRequest(query, new HashMap<>(), null))));
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    private void doRequestAsync(HttpServletRequest request, HttpServletResponse response, HttpRequestHandler handler) {
        if (asyncServletMode) {
            AsyncContext asyncContext = request.startAsync();
            HttpServletRequest asyncRequest = (HttpServletRequest) asyncContext.getRequest();
            HttpServletResponse asyncResponse = (HttpServletResponse) asyncContext.getResponse();
            new Thread(() -> doRequest(asyncRequest, asyncResponse, handler, asyncContext)).start();
        } else {
            doRequest(request, response, handler, null);
        }
    }

    private void doRequest(HttpServletRequest request, HttpServletResponse response, HttpRequestHandler handler, AsyncContext asyncContext) {

        List<GraphQLServletListener.RequestCallback> requestCallbacks = runListeners(l -> l.onRequest(request, response));

        try {
            handler.handle(request, response);
            runCallbacks(requestCallbacks, c -> c.onSuccess(request, response));
        } catch (Throwable t) {
            response.setStatus(500);
            log.error("Error executing GraphQL request!", t);
            runCallbacks(requestCallbacks, c -> c.onError(request, response, t));
        } finally {
            runCallbacks(requestCallbacks, c -> c.onFinally(request, response));
            if (asyncContext != null) {
                asyncContext.complete();
            }
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        doRequestAsync(req, resp, getHandler);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        doRequestAsync(req, resp, postHandler);
    }

    private Optional<Part> getFileItem(Map<String, List<Part>> fileItems, String name) {
        return Optional.ofNullable(fileItems.get(name)).filter(list -> !list.isEmpty()).map(list -> list.get(0));
    }

    private void query(GraphQLQueryInvoker queryInvoker, GraphQLObjectMapper graphQLObjectMapper, GraphQLSingleInvocationInput invocationInput, HttpServletResponse resp) throws IOException {
        ExecutionResult result = queryInvoker.query(invocationInput);

        resp.setContentType(APPLICATION_JSON_UTF8);
        resp.setStatus(STATUS_OK);
        resp.getWriter().write(graphQLObjectMapper.serializeResultAsJson(result));
    }

    private void queryBatched(GraphQLQueryInvoker queryInvoker, GraphQLObjectMapper graphQLObjectMapper, GraphQLBatchedInvocationInput invocationInput, HttpServletResponse resp) throws Exception {
        resp.setContentType(APPLICATION_JSON_UTF8);
        resp.setStatus(STATUS_OK);

        Writer respWriter = resp.getWriter();
        respWriter.write('[');

        queryInvoker.query(invocationInput, (result, hasNext) -> {
            respWriter.write(graphQLObjectMapper.serializeResultAsJson(result));
            if (hasNext) {
                respWriter.write(',');
            }
        });

        respWriter.write(']');
    }

    private <R> List<R> runListeners(Function<? super GraphQLServletListener, R> action) {
        if (listeners == null) {
            return Collections.emptyList();
        }

        return listeners.stream()
                .map(listener -> {
                    try {
                        return action.apply(listener);
                    } catch (Throwable t) {
                        log.error("Error running listener: {}", listener, t);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private <T> void runCallbacks(List<T> callbacks, Consumer<T> action) {
        callbacks.forEach(callback -> {
            try {
                action.accept(callback);
            } catch (Throwable t) {
                log.error("Error running callback: {}", callback, t);
            }
        });
    }

    private boolean isBatchedQuery(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return false;
        }

        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[128];
        int length;

        inputStream.mark(0);
        while ((length = inputStream.read(buffer)) != -1) {
            result.write(buffer, 0, length);
            String chunk = result.toString();
            Boolean isArrayStart = isArrayStart(chunk);
            if (isArrayStart != null) {
                inputStream.reset();
                return isArrayStart;
            }
        }

        inputStream.reset();
        return false;
    }

    private boolean isBatchedQuery(String query) {
        if (query == null) {
            return false;
        }

        Boolean isArrayStart = isArrayStart(query);
        return isArrayStart != null && isArrayStart;
    }

    // return true if the first non whitespace character is the beginning of an array
    private Boolean isArrayStart(String s) {
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (!Character.isWhitespace(ch)) {
                return ch == '[';
            }
        }

        return null;
    }

    protected interface HttpRequestHandler extends BiConsumer<HttpServletRequest, HttpServletResponse> {
        @Override
        default void accept(HttpServletRequest request, HttpServletResponse response) {
            try {
                handle(request, response);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        void handle(HttpServletRequest request, HttpServletResponse response) throws Exception;
    }
}
