package org.springframework.ws.transport.http;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.OrderComparator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.FrameworkServlet;
import org.springframework.web.util.WebUtils;
import org.springframework.ws.EndpointAdapter;
import org.springframework.ws.EndpointExceptionResolver;
import org.springframework.ws.EndpointMapping;
import org.springframework.ws.MessageDispatcher;
import org.springframework.ws.WebServiceMessageFactory;
import org.springframework.ws.wsdl.WsdlDefinition;

/**
 * Servlet for simplified dispatching of Web service messages. Delegates to a <code>MessageDispatcher</code> and a
 * <code>MessageEndpointHandlerAdapter</code>.
 * <p/>
 * This servlet is a convenient alternative for a standard Spring-MVC <code>DispatcherServlet</code> with a separate
 * <code>MessageEndpointHandlerAdapter</code> and a <code>MessageDispatcher</code>.
 * <p/>
 * This servlet automatically detects <code>EndpointAdapter</code>s, <code>EndpointMapping</code>s, and
 * <code>EndpointExceptionResolver</code>s, by type (when the corresponding detectAll* property is enabled) or by type
 * (when the property is disabled). For instance, when the <code>detectAllEndpointAdapters</code> propery is
 * <code>true</code> (the default), all endpoint adapters defined in the web application context are registered with the
 * message dispatcher. When it is set to <code>false</code>, this servlet tries to find a bean with the
 * "endpointAdapter" bean name in the context.
 *
 * @author Arjen Poutsma
 * @see org.springframework.web.servlet.DispatcherServlet
 * @see org.springframework.ws.MessageDispatcher
 * @see org.springframework.ws.transport.http.MessageEndpointHandlerAdapter
 */
public class MessageDispatcherServlet extends FrameworkServlet {

    /**
     * Well-known name for the <code>EndpointAdapter</code> object in the bean factory for this namespace. Only used
     * when <code>detectAllEndpointAdapters</code> is turned off.
     *
     * @see #setDetectAllEndpointAdapters(boolean)
     * @see org.springframework.ws.EndpointAdapter
     */
    public static final String ENDPOINT_ADAPTER_BEAN_NAME = "endpointAdapter";

    /**
     * Well-known name for the <code>EndpointExceptionResolver</code> object in the bean factory for this namespace.
     * Only used when "detectAllEndpointExceptionResolvers" is turned off.
     *
     * @see #setDetectAllEndpointExceptionResolvers
     */
    public static final String ENDPOINT_EXCEPTION_RESOLVER_BEAN_NAME = "endpointExceptionResolver";

    /**
     * Well-known name for the <code>EndpointMapping</code> object in the bean factory for this namespace. Only used
     * when "detectAllEndpointMappings" is turned off.
     *
     * @see #setDetectAllEndpointMappings
     */
    public static final String ENDPOINT_MAPPING_BEAN_NAME = "endpointMapping";

    /** Well-known name for the <code>WebServiceMessageFactory</code> object in the bean factory for this namespace. */
    public static final String WEB_SERVICE_MESSAGE_FACTORY_BEAN_NAME = "messageFactory";

    /** Well-known name for the <code>MessageDispatcher</code> object in the bean factory for this namespace. */
    public static final String MESSAGE_DISPATCHER_BEAN_NAME = "messageDispatcher";

    /**
     * Name of the class path resource (relative to the MessageDispatcherServlet class) that defines
     * MessageDispatcherServlet's default strategy names.
     */
    private static final String DEFAULT_STRATEGIES_PATH = "MessageDispatcherServlet.properties";

    /** Suffix of a WSDL request uri. */
    private static final String WSDL_SUFFIX_NAME = ".wsdl";

    private static final Properties defaultStrategies = new Properties();

    /** Detect all <code>EndpointAdapter</code>s or just expect "endpointAdapter" bean? */
    private boolean detectAllEndpointAdapters = true;

    /** Detect all <code>EndpointExceptionResolver</code>s or just expect "endpointExceptionResolver" bean? */
    private boolean detectAllEndpointExceptionResolvers = true;

    /** Detect all <code>EndpointMapping</code>s or just expect "endpointMapping" bean? */
    private boolean detectAllEndpointMappings = true;

    /** Detect all <code>WsdlDefinition</code>s? */
    private boolean detectAllWsdlDefinitions = true;

    /** The <code>MessageEndpointHandlerAdapter</code> used by this servlet. */
    private MessageEndpointHandlerAdapter messageEndpointHandlerAdapter = new MessageEndpointHandlerAdapter();

    /** The <code>WsdlDefinitionHandlerAdapter</code> used by this servlet. */
    private WsdlDefinitionHandlerAdapter wsdlDefinitionHandlerAdapter = new WsdlDefinitionHandlerAdapter();

    /** The <code>MessageDispatcher</code> used by this servlet. */
    private MessageDispatcher messageDispatcher;

    /** Keys are beans names, values are <code>WsdlDefinition</code>s. */
    private Map wsdlDefinitions;

    static {
        // Load default strategy implementations from properties file.
        // This is currently strictly internal and not meant to be customized
        // by application developers.
        try {
            ClassPathResource resource = new ClassPathResource(DEFAULT_STRATEGIES_PATH, MessageDispatcherServlet.class);
            InputStream is = resource.getInputStream();
            try {
                defaultStrategies.load(is);
            }
            finally {
                is.close();
            }
        }
        catch (IOException ex) {
            throw new IllegalStateException("Could not load '" + DEFAULT_STRATEGIES_PATH + "': " + ex.getMessage());
        }
    }

    public MessageDispatcherServlet() {
    }

    /** Returns the <code>MessageDispatcher</code> used by this servlet. */
    protected MessageDispatcher getMessageDispatcher() {
        return messageDispatcher;
    }

    /**
     * Set whether to detect all <code>EndpointAdapter</code> beans in this servlet's context. Else, just a single bean
     * with name "endpointAdapter" will be expected.
     * <p/>
     * Default is <code>true</code>. Turn this off if you want this servlet to use a single adapter, despite multiple
     * adapter beans being defined in the context.
     *
     * @see org.springframework.ws.EndpointAdapter
     */
    public void setDetectAllEndpointAdapters(boolean detectAllEndpointAdapters) {
        this.detectAllEndpointAdapters = detectAllEndpointAdapters;
    }

    /**
     * Set whether to detect all <code>EndpointExceptionResolver</code> beans in this servlet's context. Else, just a
     * single bean with name "endpointExceptionResolver" will be expected.
     * <p/>
     * Default is <code>true</code>. Turn this off if you want this servlet to use a single exception resolver, despite
     * multiple resolver beans being defined in the context.
     *
     * @see org.springframework.ws.EndpointExceptionResolver
     */
    public void setDetectAllEndpointExceptionResolvers(boolean detectAllEndpointExceptionResolvers) {
        this.detectAllEndpointExceptionResolvers = detectAllEndpointExceptionResolvers;
    }

    /**
     * Set whether to detect all <code>EndpointMapping</code> beans in this servlet's context. Else, just a single bean
     * with name "endpointMapping" will be expected.
     * <p/>
     * Default is <code>true</code>. Turn this off if you want this servlet to use a single mapping, despite multiple
     * mapping beans being defined in the context.
     *
     * @see org.springframework.ws.EndpointMapping
     */
    public void setDetectAllEndpointMappings(boolean detectAllEndpointMappings) {
        this.detectAllEndpointMappings = detectAllEndpointMappings;
    }

    /**
     * Set whether to detect all <code>WsdlDefinition</code> beans in this servlet's context.
     * <p/>
     * Default is <code>true</code>.
     *
     * @see org.springframework.ws.wsdl.WsdlDefinition
     */
    public void setDetectAllWsdlDefinitions(boolean detectAllWsdlDefinitions) {
        this.detectAllWsdlDefinitions = detectAllWsdlDefinitions;
    }

    protected long getLastModified(HttpServletRequest req) {
        return messageEndpointHandlerAdapter.getLastModified(req, messageDispatcher);
    }

    protected void initFrameworkServlet() throws ServletException, BeansException {
        initWebServiceMessageFactory();
        initMessageDispatcher();
        initWsdlDefinitions();
    }

    protected void doService(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse)
            throws Exception {
        WsdlDefinition definition = getWsdlDefinition(httpServletRequest);
        if (definition != null) {
            wsdlDefinitionHandlerAdapter.handle(httpServletRequest, httpServletResponse, definition);
        }
        else {
            messageEndpointHandlerAdapter.handle(httpServletRequest, httpServletResponse, messageDispatcher);
        }
    }

    /**
     * Determines the <code>WsdlDefinition</code> for a given request, or <code>null</code> if none is found.
     * <p/>
     * Default implementation checks whether the request method is GET, whether the request uri ends with ".wsdl", and
     * if there is a <code>WsdlDefinition</code> with the same name as the filename in the request uri.
     *
     * @param request the <code>HttpServletRequest</code>
     * @return a definition, or <code>null</code>
     * @throws Exception in case of errors
     */
    protected WsdlDefinition getWsdlDefinition(HttpServletRequest request) throws Exception {
        if ("GET".equals(request.getMethod()) && request.getRequestURI().endsWith(WSDL_SUFFIX_NAME)) {
            String fileName = WebUtils.extractFilenameFromUrlPath(request.getRequestURI());
            return (WsdlDefinition) wsdlDefinitions.get(fileName);
        }
        else {
            return null;
        }
    }

    /**
     * Create a List of default strategy objects for the given strategy interface.
     * <p/>
     * The default implementation uses the "MessageDispatcherServlet.properties" file (in the same package as the
     * MessageDispatcherServlet class) to determine the class names. It instantiates the strategy objects and satisifies
     * ApplicationContextAware if necessary.
     *
     * @param strategyInterface the strategy interface
     * @return the List of corresponding strategy objects
     * @throws BeansException if initialization failed
     * @see #DEFAULT_STRATEGIES_PATH
     */
    protected List getDefaultStrategies(Class strategyInterface) throws BeansException {
        String key = strategyInterface.getName();
        try {
            List strategies = null;
            String value = defaultStrategies.getProperty(key);
            if (value != null) {
                String[] classNames = StringUtils.commaDelimitedListToStringArray(value);
                strategies = new ArrayList(classNames.length);
                for (int i = 0; i < classNames.length; i++) {
                    Class clazz = Class.forName(classNames[i], true, getClass().getClassLoader());
                    Object strategy = BeanUtils.instantiateClass(clazz);
                    if (strategy instanceof ApplicationContextAware) {
                        ((ApplicationContextAware) strategy).setApplicationContext(getWebApplicationContext());
                    }
                    strategies.add(strategy);
                }
            }
            else {
                strategies = Collections.EMPTY_LIST;
            }
            return strategies;
        }
        catch (ClassNotFoundException ex) {
            throw new BeanInitializationException(
                    "Could not find DispatcherServlet's default strategy class for interface [" + key + "]", ex);
        }
    }

    /**
     * Return the default strategy object for the given strategy interface.
     * <p/>
     * Default implementation delegates to <code>getDefaultStrategies</code>, expecting a single object in the list.
     *
     * @param strategyInterface the strategy interface
     * @return the corresponding strategy object
     * @throws BeansException if initialization failed
     * @see #getDefaultStrategies
     */
    protected Object getDefaultStrategy(Class strategyInterface) throws BeansException {
        List strategies = getDefaultStrategies(strategyInterface);
        if (strategies.size() != 1) {
            throw new BeanInitializationException(
                    "MessageDispatcherServlet needs exactly 1 strategy for [" + strategyInterface.getName() + "]");
        }
        return strategies.get(0);
    }

    /**
     * Initialize the <code>EndpointAdapters</code> used by this class. If no adapter beans are defined in the bean
     * factory for this namespace, we default to the strategies in <code>MessageDispatcher</code>.
     *
     * @see org.springframework.ws.MessageDispatcher#initDefaultStrategies()
     */
    private void initEndpointAdapters() throws BeansException {
        if (detectAllEndpointAdapters) {
            // Find all EndpointAdapters in the ApplicationContext, including ancestor contexts.
            Map matchingBeans = BeanFactoryUtils
                    .beansOfTypeIncludingAncestors(getWebApplicationContext(), EndpointAdapter.class, true, false);
            if (!matchingBeans.isEmpty()) {
                List endpointAdapters = new ArrayList(matchingBeans.values());
                // We keep EndpointAdapters in sorted order.
                Collections.sort(endpointAdapters, new OrderComparator());
                messageDispatcher.setEndpointAdapters(endpointAdapters);
            }
        }
        else {
            try {
                Object endointAdapter =
                        getWebApplicationContext().getBean(ENDPOINT_ADAPTER_BEAN_NAME, EndpointAdapter.class);
                messageDispatcher.setEndpointAdapters(Collections.singletonList(endointAdapter));
            }
            catch (NoSuchBeanDefinitionException ex) {
                // Ignore, we'll use the default adapters of MessageDispatcher
            }
        }
    }

    /**
     * Initialize the <code>EndpointExceptionResolver</code> used by this class. If no bean is defined with the given
     * name in the BeanFactory for this namespace, we default to the strategies in <code>MessageDispatcher</code>.
     */
    private void initEndpointExceptionResolvers() throws BeansException {
        if (detectAllEndpointExceptionResolvers) {
            // Find all EndpointExceptionResolvers in the ApplicationContext, including ancestor contexts.
            Map matchingBeans = BeanFactoryUtils.beansOfTypeIncludingAncestors(getWebApplicationContext(),
                    EndpointExceptionResolver.class, true, false);
            if (!matchingBeans.isEmpty()) {
                List endpointExceptionResolvers = new ArrayList(matchingBeans.values());
                // We keep EndpointExceptionResolvers in sorted order.
                Collections.sort(endpointExceptionResolvers, new OrderComparator());
                messageDispatcher.setEndpointExceptionResolvers(endpointExceptionResolvers);
            }
        }
        else {
            try {
                Object endpointExceptionResolver = getWebApplicationContext()
                        .getBean(ENDPOINT_EXCEPTION_RESOLVER_BEAN_NAME, EndpointExceptionResolver.class);
                messageDispatcher.setEndpointExceptionResolvers(Collections.singletonList(endpointExceptionResolver));
            }
            catch (NoSuchBeanDefinitionException ex) {
                // Ignore, we'll use the default adapters of MessageDispatcher
            }
        }
    }

    /**
     * Initialize the <code>EndpointMappings</code> used by this class. If no mapping beans are defined in the
     * BeanFactory for this namespace, we default to the strategies in <code>MessageDispatcher</code>.
     *
     * @see org.springframework.ws.MessageDispatcher#initDefaultStrategies()
     */
    private void initEndpointMappings() throws BeansException {
        if (detectAllEndpointMappings) {
            // Find all EndpointMappings in the ApplicationContext, including ancestor contexts.
            Map matchingBeans = BeanFactoryUtils
                    .beansOfTypeIncludingAncestors(getWebApplicationContext(), EndpointMapping.class, true, false);
            if (!matchingBeans.isEmpty()) {
                List endpointMappings = new ArrayList(matchingBeans.values());
                // We keep EndpointMappings in sorted order.
                Collections.sort(endpointMappings, new OrderComparator());
                messageDispatcher.setEndpointMappings(endpointMappings);
            }
        }
        else {
            try {
                Object endpointMapping =
                        getWebApplicationContext().getBean(ENDPOINT_MAPPING_BEAN_NAME, EndpointMapping.class);
                messageDispatcher.setEndpointMappings(Collections.singletonList(endpointMapping));
            }
            catch (NoSuchBeanDefinitionException ex) {
                // Ignore, we'll use the default adapters of MessageDispatcher
            }
        }
    }

    private void initWebServiceMessageFactory() throws BeansException {
        WebServiceMessageFactory messageFactory;
        try {
            messageFactory = (WebServiceMessageFactory) getWebApplicationContext()
                    .getBean(WEB_SERVICE_MESSAGE_FACTORY_BEAN_NAME, WebServiceMessageFactory.class);
        }
        catch (NoSuchBeanDefinitionException ignored) {
            messageFactory = (WebServiceMessageFactory) getDefaultStrategy(WebServiceMessageFactory.class);
            if (logger.isInfoEnabled()) {
                logger.info("Unable to locate WebServiceMessageFactory with name '" +
                        WEB_SERVICE_MESSAGE_FACTORY_BEAN_NAME + "': using default [" + messageFactory + "]");
            }
            if (messageFactory instanceof InitializingBean) {
                try {
                    ((InitializingBean) messageFactory).afterPropertiesSet();
                }
                catch (Exception ex) {
                    throw new BeanInitializationException("Could not invoke afterPropertiesSet() on message factory",
                            ex);
                }
            }
        }
        messageEndpointHandlerAdapter.setMessageFactory(messageFactory);
    }

    private void initMessageDispatcher() {
        try {
            messageDispatcher = (MessageDispatcher) getWebApplicationContext()
                    .getBean(MESSAGE_DISPATCHER_BEAN_NAME, MessageDispatcher.class);
        }
        catch (NoSuchBeanDefinitionException ex) {
            messageDispatcher = (MessageDispatcher) getDefaultStrategy(MessageDispatcher.class);
            messageDispatcher.setBeanName(getServletName());
            if (logger.isInfoEnabled()) {
                logger.info("Unable to locate MessageDispatcher with name '" + MESSAGE_DISPATCHER_BEAN_NAME +
                        "': using default [" + messageDispatcher + "]");
            }
            // We only autodetect the following strategies when a message dispatcher has not been defined in
            // the application context
            initEndpointMappings();
            initEndpointAdapters();
            initEndpointExceptionResolvers();
        }
    }

    private void initWsdlDefinitions() {
        if (detectAllWsdlDefinitions) {
            // Find all WsdlDefinitions in the ApplicationContext, incuding ancestor contexts.
            wsdlDefinitions = BeanFactoryUtils
                    .beansOfTypeIncludingAncestors(getWebApplicationContext(), WsdlDefinition.class, true, false);
        }
    }
}
