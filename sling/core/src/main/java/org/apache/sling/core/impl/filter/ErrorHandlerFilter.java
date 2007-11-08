/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.core.impl.filter;

import java.io.IOException;
import java.util.Iterator;

import javax.servlet.FilterChain;
import javax.servlet.Servlet;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingConstants;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.wrappers.SlingHttpServletResponseWrapper;
import org.apache.sling.core.impl.helper.ContentData;
import org.apache.sling.core.impl.helper.RequestData;
import org.apache.sling.core.servlets.ErrorHandlerServlet;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.ComponentException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The <code>ErrorHandlerFilter</code> TODO
 *
 * @scr.component immediate="true" label="%errhandler.name"
 *                description="%errhandler.description"
 * @scr.property name="service.description" value="Error Handler Filter"
 * @scr.property name="service.vendor" value="The Apache Software Foundation"
 * @scr.property name="filter.scope" value="request" private="true"
 * @scr.property name="filter.order" value="-1000" type="Integer" private="true"
 * @scr.service
 * @scr.reference name="Servlets"
 *                interface="org.apache.sling.core.servlets.ErrorHandlerServlet"
 *                cardinality="0..n" policy="dynamic"
 */
public class ErrorHandlerFilter extends ServletBindingFilter {

    /** default log */
    private static final Logger log = LoggerFactory.getLogger(ErrorHandlerFilter.class);

    public void doFilter(ServletRequest req, ServletResponse res, FilterChain filterChain)
            throws IOException {

        SlingHttpServletRequest request = (SlingHttpServletRequest) req;
        SlingHttpServletResponse response = (SlingHttpServletResponse) res;

        try {
            filterChain.doFilter(request, new ErrorHandlerResponse(request,
                response));
        } catch (Throwable throwable) {
            log.error("Error during request processing.", throwable);
            handleError(throwable, request, response);
        }

    }

    @Override
    protected void servletBound(ServiceReference reference, Servlet servlet) {
    }

    @Override
    protected void servletUnbound(ServiceReference reference, Servlet servlet) {
    }

    // ---------- internal -----------------------------------------------------

    // TODO: this must be called from SlingHttpServletResponseImpl...
    private void handleError(int status, String message,
            SlingHttpServletRequest request, SlingHttpServletResponse response)
            throws IOException {

        // do not handle, if already handling ....
        if (request.getAttribute(SlingConstants.ERROR_REQUEST_URI) == null) {

            // find the error handler component
            int checkStatus = status;
            int filter = 10;
            for (;;) {
                for (Iterator<Servlet> hi = getServlets(); hi.hasNext();) {
                    ErrorHandlerServlet handler = (ErrorHandlerServlet) hi.next();
                    if (handler.canHandle(checkStatus)) {

                        // set the message properties
                        request.setAttribute(SlingConstants.ERROR_STATUS,
                            new Integer(status));
                        request.setAttribute(SlingConstants.ERROR_MESSAGE,
                            message);

                        if (handleError(handler, request, response)) {
                            return;
                        }
                    }
                }

                // nothing more to be done
                if (checkStatus == 0) {
                    break;
                }

                // cut off last position
                checkStatus = (checkStatus / filter) * filter;
                filter *= 10;
            }
        }

        // get here, if we have no handler, let the status go up the chain
        // and if this causes and exception, so what ...
        response.sendError(status, message);
    }

    private void handleError(Throwable throwable,
            SlingHttpServletRequest request, SlingHttpServletResponse response)
            throws ComponentException, IOException {

        // do not handle, if already handling ....
        if (request.getAttribute(SlingConstants.ERROR_REQUEST_URI) == null) {

            // find the error handler component
            Class<?> tClass = throwable.getClass();
            while (tClass != null) {
                String tClassName = tClass.getName();
                for (Iterator<Servlet> hi = getServlets(); hi.hasNext();) {
                    ErrorHandlerServlet handler = (ErrorHandlerServlet) hi.next();
                    if (handler.canHandle(tClassName)) {

                        // set the message properties
                        request.setAttribute(SlingConstants.ERROR_EXCEPTION,
                            throwable);
                        request.setAttribute(
                            SlingConstants.ERROR_EXCEPTION_TYPE,
                            throwable.getClass());
                        request.setAttribute(SlingConstants.ERROR_MESSAGE,
                            throwable.getMessage());

                        if (handleError(handler, request, response)) {
                            return;
                        }
                    }
                }

                // go to the base class
                tClass = tClass.getSuperclass();
            }
        }

        // get here, if we have no handler, let the throwable go up the chain
        if (throwable instanceof IOException) {
            throw (IOException) throwable;
        } else if (throwable instanceof ComponentException) {
            throw (ComponentException) throwable;
        } else if (throwable instanceof RuntimeException) {
            throw (RuntimeException) throwable;
        } else {
            throw new ComponentException(throwable);
        }
    }

    private boolean handleError(ErrorHandlerServlet errorHandler,
            SlingHttpServletRequest request, SlingHttpServletResponse response) {

        // set the message properties
        try {
            RequestData requestData = RequestData.getRequestData(request);
            ContentData contentData = requestData.getContentData();
            if (contentData != null && contentData.getResource() != null) {
                request.setAttribute(
                    SlingConstants.ERROR_SERVLET_NAME,
                    contentData.getServlet().getServletConfig().getServletName());
            }
        } catch (ComponentException ce) {
            log.warn("handleError: Called with wrong request type, ignore for now");
        }

        request.setAttribute(SlingConstants.ERROR_REQUEST_URI,
            request.getRequestURI());

        // return but keep the attribute to prevent repeated error handling !
        // TODO: Should actually do better for finding about whether we already
        // handle errors -> maybe a RequestData flag ??
        if (errorHandler == null) {
            return false;
        }

        if (request.getAttribute(SlingConstants.ERROR_SERVLET_NAME) == null) {
            request.setAttribute(SlingConstants.ERROR_SERVLET_NAME,
                errorHandler.getServletConfig().getServletName());
        }

        // find a component by
        try {
            errorHandler.service(request, response);
            return true;
        } catch (Throwable t) {
            log.error("Cannot handle error", t);
        }

        return false;
    }

    // ---------- internal class -----------------------------------------------

    private class ErrorHandlerResponse extends SlingHttpServletResponseWrapper {

        private SlingHttpServletRequest handlerRequest;

        public ErrorHandlerResponse(SlingHttpServletRequest handlerRequest,
                SlingHttpServletResponse delegatee) {
            super(delegatee);
            this.handlerRequest = handlerRequest;
        }

        public void sendError(int status) throws IOException {
            checkCommitted();
            handleError(status, null, handlerRequest, getSlingResponse());
        }

        public void sendError(int status, String message) throws IOException {
            checkCommitted();
            handleError(status, message, handlerRequest, getSlingResponse());
        }

        public void sendRedirect(String location) {
            checkCommitted();

            // TODO: location must be converted into an absolute URL and
            // link-checked

            setStatus(HttpServletResponse.SC_MOVED_TEMPORARILY);
            setHeader("Location", location);
        }

        private void checkCommitted() {
            if (isCommitted()) {
                throw new IllegalStateException(
                    "Response has already been committed");
            }
        }
    }

}
