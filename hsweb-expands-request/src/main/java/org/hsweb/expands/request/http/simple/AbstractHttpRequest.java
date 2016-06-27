package org.hsweb.expands.request.http.simple;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.*;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.hsweb.expands.request.http.Callback;
import org.hsweb.expands.request.http.HttpDownloader;
import org.hsweb.expands.request.http.HttpRequest;

import java.io.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Created by zhouhao on 16-6-23.
 */
public abstract class AbstractHttpRequest<R> implements HttpRequest<R> {
    private Map<String, String> params = new LinkedHashMap<>();
    private Map<String, String> headers = new LinkedHashMap<>();
    private String url;
    private String requestBody;
    private String contentType;
    private String encode = "utf-8";
    private Callback<HttpUriRequest> before;
    private Callback<HttpResponse> after;
    protected HttpClient httpClient;

    public AbstractHttpRequest(String url) {
        this.url = url;
        if (httpClient == null) {
            HttpClientBuilder builder = HttpClientBuilder.create();
            httpClient = builder.build();
        }
    }

    public AbstractHttpRequest(String url, HttpClient client) {
        this.url = url;
        this.httpClient = client;
    }

    @Override
    public void close() throws IOException {
        if (httpClient != null && httpClient instanceof CloseableHttpClient) {
            ((CloseableHttpClient) httpClient).close();
        }
    }

    @Override
    public HttpRequest<R> before(Callback<HttpUriRequest> callback) {
        this.before = callback;
        return this;
    }

    @Override
    public HttpRequest<R> after(Callback<HttpResponse> callback) {
        this.after = callback;
        return this;
    }

    @Override
    public HttpRequest<R> resultAsJsonString() {
        header("Accept", "application/json");
        return this;
    }

    @Override
    public HttpRequest<R> requestBody(String body) {
        contentType("application/json");
        this.requestBody = body;
        return this;
    }

    @Override
    public HttpRequest<R> encode(String encode) {
        this.encode = encode;
        return this;
    }

    @Override
    public HttpRequest<R> contentType(String type) {
        this.contentType = type;
        return this;
    }

    @Override
    public HttpRequest<R> param(String name, String value) {
        this.params.put(name, value);
        return this;
    }

    @Override
    public HttpRequest<R> params(Map<String, String> params) {
        this.params.putAll(params);
        return this;
    }

    @Override
    public HttpRequest<R> header(String name, String value) {
        this.headers.put(name, value);
        return this;
    }

    @Override
    public HttpRequest<R> headers(Map<String, String> header) {
        this.headers.putAll(header);
        return this;
    }

    @Override
    public HttpRequest<R> cookie(String cookie) {
        header("Cookie", cookie);
        return this;
    }

    protected void doBefore(HttpUriRequest request) {
        if (before != null) {
            before.accept(request);
        }
    }

    protected void doAfter(HttpResponse response) {
        if (after != null) {
            after.accept(response);
        }
    }

    @Override
    public HttpDownloader<R> download() throws IOException {
        return new HttpDownloader<R>() {
            private HttpResponse response;

            @Override
            public HttpDownloader<R> get() throws IOException {
                List<NameValuePair> nameValuePair = params.entrySet()
                        .stream().map(stringStringEntry ->
                                new BasicNameValuePair(stringStringEntry.getKey(), stringStringEntry.getValue()))
                        .collect(Collectors.toList());
                UrlEncodedFormEntity formEntity = new UrlEncodedFormEntity(nameValuePair, encode);
                String param = EntityUtils.toString(formEntity);
                String tmpUrl = url + (url.contains("?") ? "&" : "?") + param;
                HttpGet get = new HttpGet(tmpUrl);
                response = execute(get);
                return this;
            }

            @Override
            public HttpDownloader<R> post() throws IOException {
                HttpPost post = new HttpPost(url);
                if (requestBody != null)
                    post.setEntity(new StringEntity(requestBody, ContentType.create(contentType)));
                else {
                    List<NameValuePair> nameValuePair = params.entrySet()
                            .stream().map(stringStringEntry ->
                                    new BasicNameValuePair(stringStringEntry.getKey(), stringStringEntry.getValue()))
                            .collect(Collectors.toList());
                    UrlEncodedFormEntity formEntity = new UrlEncodedFormEntity(nameValuePair, encode);
                    post.setEntity(formEntity);
                }
                response = execute(post);
                return this;
            }

            @Override
            public R write(File file) throws IOException {
                if (response == null) get();
                if (file.isFile()) {
                    return write(new FileOutputStream(file));
                } else if (file.isDirectory()) {
                    HttpEntity entity = response.getEntity();
                    Header header = response.getFirstHeader("Content-disposition");
                    String fileName;
                    if (header != null) {
                        String[] tmp = header.getValue().split("[;]");
                        fileName = tmp[1].split("[=]")[1];
                    } else {
                        if (url.contains("/")) {
                            String[] tmp = url.split("[/]");
                            fileName = tmp[tmp.length - 1];
                        } else {
                            fileName = "unknow";
                        }
                    }
                    FileOutputStream outputStream = new FileOutputStream(new File(file, fileName));
                    if (response.getStatusLine().getStatusCode() == 200) {
                        InputStream inputStream = entity.getContent();
                        int b;
                        while ((b = inputStream.read()) != -1) {
                            outputStream.write(b);
                        }
                        EntityUtils.consumeQuietly(entity);
                        return null;
                    } else {
                        return getResultValue(response);
                    }
                }
                throw new IOException("file cannot write");
            }

            @Override
            public R write(OutputStream outputStream) throws IOException {
                if (response == null) get();
                HttpEntity entity = response.getEntity();
                if (response.getStatusLine().getStatusCode() == 200) {
                    InputStream inputStream = entity.getContent();
                    int b;
                    while ((b = inputStream.read()) != -1) {
                        outputStream.write(b);
                    }
                    EntityUtils.consumeQuietly(entity);
                    return null;
                } else {
                    return getResultValue(response);
                }
            }
        };
    }

    protected HttpResponse download(String a) throws IOException {
        List<NameValuePair> nameValuePair = params.entrySet()
                .stream().map(stringStringEntry ->
                        new BasicNameValuePair(stringStringEntry.getKey(), stringStringEntry.getValue()))
                .collect(Collectors.toList());
        UrlEncodedFormEntity formEntity = new UrlEncodedFormEntity(nameValuePair, encode);
        String param = EntityUtils.toString(formEntity);
        String tmpUrl = url + (url.contains("?") ? "&" : "?") + param;
        HttpGet get = new HttpGet(tmpUrl);
        return execute(get);
    }


    @Override
    public R upload(File file) throws IOException {
        return upload("file", file);
    }

    @Override
    public R upload(String paramName, File file) throws IOException {
        HttpPost post = new HttpPost(url);
        MultipartEntityBuilder builder = MultipartEntityBuilder.create()
                .addPart("paramName", new FileBody(file));
        params.forEach(builder::addTextBody);
        post.setEntity(builder.build());
        HttpResponse response = execute(post);
        return getResultValue(response);
    }

    protected void putHeader(HttpUriRequest request) {
        headers.forEach(request::setHeader);
    }

    protected HttpResponse execute(HttpRequestBase request) throws IOException {
        putHeader(request);
        doBefore(request);
        HttpResponse response = httpClient.execute(request);
        doAfter(response);
        return response;
    }

    @Override
    public R get() throws IOException {
        List<NameValuePair> nameValuePair = params.entrySet()
                .stream().map(stringStringEntry ->
                        new BasicNameValuePair(stringStringEntry.getKey(), stringStringEntry.getValue()))
                .collect(Collectors.toList());
        UrlEncodedFormEntity formEntity = new UrlEncodedFormEntity(nameValuePair, encode);
        String param = EntityUtils.toString(formEntity);
        String tmpUrl = url + (url.contains("?") ? "&" : "?") + param;
        HttpGet get = new HttpGet(tmpUrl);
        HttpResponse response = execute(get);
        return getResultValue(response);
    }


    @Override
    public R post() throws IOException {
        HttpPost post = new HttpPost(url);
        if (requestBody != null)
            post.setEntity(new StringEntity(requestBody, ContentType.create(contentType)));
        else {
            List<NameValuePair> nameValuePair = params.entrySet()
                    .stream().map(stringStringEntry ->
                            new BasicNameValuePair(stringStringEntry.getKey(), stringStringEntry.getValue()))
                    .collect(Collectors.toList());
            UrlEncodedFormEntity formEntity = new UrlEncodedFormEntity(nameValuePair, encode);
            post.setEntity(formEntity);
        }
        HttpResponse response = execute(post);
        return getResultValue(response);
    }

    @Override
    public R put() throws IOException {
        HttpPut put = new HttpPut(url);
        if (requestBody != null)
            put.setEntity(new StringEntity(requestBody, ContentType.create(contentType)));
        else {
            List<NameValuePair> nameValuePair = params.entrySet()
                    .stream().map(stringStringEntry ->
                            new BasicNameValuePair(stringStringEntry.getKey(), stringStringEntry.getValue()))
                    .collect(Collectors.toList());
            UrlEncodedFormEntity formEntity = new UrlEncodedFormEntity(nameValuePair, encode);
            put.setEntity(formEntity);
        }
        HttpResponse response = execute(put);
        return getResultValue(response);
    }

    @Override
    public R delete() throws IOException {
        HttpDelete delete = new HttpDelete(url);
        HttpResponse response = execute(delete);
        return getResultValue(response);
    }

    @Override
    public R patch() throws IOException {
        HttpPatch delete = new HttpPatch(url);
        if (requestBody != null)
            delete.setEntity(new StringEntity(requestBody, ContentType.create(contentType)));
        else {
            List<NameValuePair> nameValuePair = params.entrySet()
                    .stream().map(stringStringEntry ->
                            new BasicNameValuePair(stringStringEntry.getKey(), stringStringEntry.getValue()))
                    .collect(Collectors.toList());
            UrlEncodedFormEntity formEntity = new UrlEncodedFormEntity(nameValuePair, encode);
            delete.setEntity(formEntity);
        }
        return getResultValue(execute(delete));
    }

    protected abstract R getResultValue(HttpResponse res) throws IOException;

}