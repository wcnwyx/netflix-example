package com.wcnwyx.zuul.example;

import com.netflix.zuul.ZuulFilter;
import com.netflix.zuul.constants.ZuulHeaders;
import com.netflix.zuul.context.RequestContext;

import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

public class PostRouteFilter extends ZuulFilter {
    @Override
    public int filterOrder() {
        return 50000;
    }

    @Override
    public String filterType() {
        return "post";
    }

    @Override
    public boolean shouldFilter() {
        return true;
    }

    @Override
    public Object run() {
        System.out.println("running javaPostFilter");
        try {
            writeResponse();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    void writeResponse() throws IOException {
        RequestContext context = RequestContext.getCurrentContext();

        if (context.getResponseBody() == null && context.getResponseDataStream() == null) {
            return;
        };

        HttpServletResponse servletResponse = context.getResponse();
        servletResponse.setCharacterEncoding("UTF-8");

        OutputStream outStream = servletResponse.getOutputStream();
        InputStream is = null;
        try {
            if (RequestContext.getCurrentContext().getResponseBody() != null) {
                String body = RequestContext.getCurrentContext().getResponseBody();
                writeResponse(new ByteArrayInputStream(body.getBytes(Charset.forName("UTF-8"))), outStream);
                return;
            }

            boolean isGzipRequested = false;
            final String requestEncoding = context.getRequest().getHeader(ZuulHeaders.ACCEPT_ENCODING);
            if (requestEncoding != null && requestEncoding.equals("gzip")) {
                isGzipRequested = true;
            }

            is = context.getResponseDataStream();
            InputStream inputStream = is;
            if (is != null) {
                if (context.sendZuulResponse()) {
                    if (context.getResponseGZipped() && !isGzipRequested) {
                        try {
                            inputStream = new GZIPInputStream(is);
                        } catch (ZipException e) {
                            e.printStackTrace();
                            inputStream = is;
                        }
                    } else if (context.getResponseGZipped() && isGzipRequested) {
                        servletResponse.setHeader(ZuulHeaders.CONTENT_ENCODING, "gzip");
                    }
                    writeResponse(inputStream, outStream);
                }
            }

        } finally {
            try {
                if(is!=null){
                    is.close();
                }
                outStream.flush();
                outStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    void writeResponse(InputStream zin, OutputStream out) throws IOException {
        byte[] bytes = new byte[1024];
        int bytesRead = -1;
        while ((bytesRead = zin.read(bytes)) != -1) {

            try {
                out.write(bytes, 0, bytesRead);
                out.flush();
            } catch (IOException e) {
                e.printStackTrace();
            }

            // doubles buffer size if previous read filled it
            if (bytesRead == bytes.length) {
                bytes = new byte[bytes.length * 2];
            }
        }
    }

}
