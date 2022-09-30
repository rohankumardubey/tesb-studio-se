// ============================================================================
//
// Copyright (C) 2006-2021 Talend Inc. - www.talend.com
//
// This source code is available under agreement available at
// %InstallDIR%\features\org.talend.rcp.branding.%PRODUCTNAME%\%PRODUCTNAME%license.txt
//
// You should have received a copy of the agreement
// along with this program; if not, write to Talend SA
// 9 rue Pages 92150 Suresnes, France
//
// ============================================================================
package org.talend.camel.designer.ui.wizards.export;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.dom4j.Attribute;
import org.dom4j.Document;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.dom4j.Namespace;
import org.dom4j.QName;
import org.dom4j.io.SAXReader;
import org.dom4j.tree.DefaultAttribute;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.runtime.IPath;
import org.talend.camel.core.model.camelProperties.CamelProcessItem;
import org.talend.core.GlobalServiceRegister;
import org.talend.core.model.general.ModuleNeeded;
import org.talend.core.model.process.ElementParameterParser;
import org.talend.core.model.process.IProcess;
import org.talend.core.model.properties.ProcessItem;
import org.talend.core.repository.constants.FileConstants;
import org.talend.core.runtime.process.ITalendProcessJavaProject;
import org.talend.core.runtime.process.TalendProcessOptionConstants;
import org.talend.designer.camel.dependencies.core.DependenciesResolver;
import org.talend.designer.camel.resource.core.model.ResourceDependencyModel;
import org.talend.designer.camel.resource.core.util.RouteResourceUtil;
import org.talend.designer.core.IDesignerCoreService;
import org.talend.designer.core.model.utils.emf.talendfile.ConnectionType;
import org.talend.designer.core.model.utils.emf.talendfile.NodeType;
import org.talend.designer.runprocess.IProcessor;
import org.talend.designer.runprocess.IRunProcessService;
import org.talend.librariesmanager.model.ModulesNeededProvider;
import org.talend.repository.RepositoryPlugin;
import org.talend.repository.constants.BuildJobConstants;
import org.talend.repository.documentation.ExportFileResource;
import org.talend.repository.ui.wizards.exportjob.scriptsmanager.esb.DataSourceConfig;
import org.talend.repository.utils.EmfModelUtils;
import org.talend.repository.utils.TemplateProcessor;
import org.talend.utils.io.FilesUtils;

import aQute.bnd.osgi.Analyzer;

/**
 * DOC ycbai class global comment. Detailled comment
 */
public class RouteJavaScriptOSGIForESBManager extends AdaptedJobJavaScriptOSGIForESBManager {

    private static final String CONVERT_SPRING_IMPORT_PROPERTY = "org.talend.esb.route.spring.import.convert"; //$NON-NLS-1$

    private static final String CONVERT_CAMEL_CONTEXT_PROPERTY = "org.talend.esb.route.spring.camel.convert"; //$NON-NLS-1$

    private static final String TEMPLATE_BLUEPRINT_ROUTE = "/resources/blueprint-template.xml"; //$NON-NLS-1$

    private static final String IMPORT_RESOURCE_PREFIX = ".." + File.separator + ".." + File.separator; //$NON-NLS-1$

    private static final String BLUEPRINT_NSURI = "http://www.osgi.org/xmlns/blueprint/v1.0.0"; //$NON-NLS-1$

    private static final String CAMEL_SPRING_NSURI = "http://camel.apache.org/schema/spring"; //$NON-NLS-1$

    private static final String CAMEL_CXF_NSURI = "http://camel.apache.org/schema/cxf"; //$NON-NLS-1$

    private static final String CAMEL_BLUEPRINT_NSURI = "http://camel.apache.org/schema/blueprint"; //$NON-NLS-1$

    private static final String CAMEL_BLUEPRINT_CXF_NSURI = "http://camel.apache.org/schema/blueprint/cxf"; //$NON-NLS-1$

    private static final String SPRING_BEANS_NSURI = "http://www.springframework.org/schema/beans";

    private static final boolean CONVERT_SPRING_IMPORT = isNotNegated(CONVERT_SPRING_IMPORT_PROPERTY);

    private static final boolean CONVERT_CAMEL_CONTEXT = isNotNegated(CONVERT_CAMEL_CONTEXT_PROPERTY);

    private static final boolean CONVERT_CAMEL_CONTEXT_ALL = isAll(CONVERT_CAMEL_CONTEXT_PROPERTY);

    private final Collection<String> routelets;

    private List<ModuleNeeded> defaultModulesNeededForBeans;

    private Set<String> modulesProvidedByFeatures;

    /*
     * Contains manifest Import-Package entries for subjobs used by cTalendJob components
     * Key - ProcessItem.id of the route
     * Value - Import-package string
     */
    private Map<String, String> subjobImportPackages = null;
    private Map<String, String> subjobRequireBundles = null;

    public RouteJavaScriptOSGIForESBManager(Map<ExportChoice, Object> exportChoiceMap, String contextName,
            Collection<String> routelets, Set<String> modulesProvidedByFeatures) {
        super(exportChoiceMap, contextName, null, IProcessor.NO_STATISTICS, IProcessor.NO_TRACES);
        this.routelets = routelets;
        this.defaultModulesNeededForBeans = ModulesNeededProvider.getModulesNeededForBeans();
        this.modulesProvidedByFeatures = modulesProvidedByFeatures;
    }

    @Override
    protected ExportFileResource getCompiledLibExportFileResource(ExportFileResource[] processes) {
        ExportFileResource libResource = new ExportFileResource(null, LIBRARY_FOLDER_NAME);
        List<URL> talendLibraries = getExternalLibraries(true, processes, getCompiledModuleNames());
        if (defaultModulesNeededForBeans == null) {
            defaultModulesNeededForBeans = ModulesNeededProvider.getModulesNeededForBeans(processes);
        }
        if (talendLibraries != null) {
            for (URL libUrl : talendLibraries) {
                boolean addRes = true;
                // TESB-21485: Exclude DEFAULT beans model

                for (ModuleNeeded need : defaultModulesNeededForBeans) {
                    if (need.getId() != null && libUrl.getFile().contains(need.getId())) {
                        addRes = false;
                        break;
                    }
                }
                if (addRes) {
                    libResource.addResource("", libUrl);
                }
            }
        }

        addRoutinesResources(processes, libResource);
        return libResource;
    }

    /**
     * DOC yyan RouteJavaScriptOSGIForESBManager constructor comment.
     *
     * @param exportChoice
     * @param context
     * @param routelets2
     * @param statisticsPort
     * @param tracePort
     */
    public RouteJavaScriptOSGIForESBManager(Map<ExportChoice, Object> exportChoiceMap, String context,
            Collection<String> routelets, Set<String> modulesProvidedByFeatures, int statisticsPort, int tracePort) {
        super(exportChoiceMap, context, null, statisticsPort, tracePort);
        this.routelets = routelets;
        this.modulesProvidedByFeatures = modulesProvidedByFeatures;
    }

    public void setSubjobImportPackages(Map<String, String> importPackages) {
        this.subjobImportPackages = importPackages;
    }

    public void setSubjobRequireBundles(Map<String, String> requireBundles) {
        this.subjobRequireBundles = requireBundles;
    }

    public static String getClassName(ProcessItem processItem) {
        return getPackageName(processItem) + PACKAGE_SEPARATOR + processItem.getProperty().getLabel();
    }

    @Override
    protected Collection<String> getRoutinesPaths() {
        final Collection<String> include = new ArrayList<String>();
        include.add(USER_BEANS_PATH);
        include.add(SYSTEM_ROUTINES_PATH);
        return include;
    }

    // Add Route Resource http://jira.talendforge.org/browse/TESB-6227
    @Override
    protected void addResources(ExportFileResource osgiResource, ProcessItem processItem) throws Exception {
        IFolder srcFolder = null;
        if (GlobalServiceRegister.getDefault().isServiceRegistered(IRunProcessService.class)) {
            IRunProcessService processService = GlobalServiceRegister.getDefault().getService(IRunProcessService.class);
            ITalendProcessJavaProject talendProcessJavaProject =
                    processService.getTalendJobJavaProject(processItem.getProperty());
            if (talendProcessJavaProject != null) {
                srcFolder = talendProcessJavaProject.getExternalResourcesFolder();
            }
        }
        if (srcFolder == null) {
            return;
        }
        IPath srcPath = srcFolder.getLocation();

        // http://jira.talendforge.org/browse/TESB-6437
        // https://jira.talendforge.org/browse/TESB-7893
        Collection<IPath> routeResource = RouteResourceUtil.synchronizeRouteResource(processItem);
        if (routeResource != null) {
            for (IPath path : RouteResourceUtil.synchronizeRouteResource(processItem)) {
                osgiResource
                        .addResource(path.removeLastSegments(1).makeRelativeTo(srcPath).toString(),
                                path.toFile().toURI().toURL());
            }
        }
    }

    @Override
    protected void generateConfig(ExportFileResource osgiResource, ProcessItem processItem, IProcess process)
            throws IOException {

        boolean needBlueprint = true;

        for (String componentName : BuildJobConstants.esbComponents) {
            if (EmfModelUtils.getComponentByName(processItem, componentName) != null) {
                needBlueprint = false;
                break;
            }
        }

        String springContent = null;
        if (processItem instanceof CamelProcessItem) {
            springContent = ((CamelProcessItem) processItem).getSpringContent();
        }

        Map<String, Object> collectRouteInfo = collectRouteInfo(processItem, process);
        
        Map<String, Element> needHandleElements = new HashMap<>();

        if (springContent != null && springContent.length() > 0) {
            String springTargetFilePath = collectRouteInfo.get("name").toString().toLowerCase() + ".xml"; //$NON-NLS-1$
            InputStream springContentInputStream = new ByteArrayInputStream(springContent.getBytes());
            
            handleSpringXml(springTargetFilePath, processItem, springContentInputStream, osgiResource, true,
                    CONVERT_SPRING_IMPORT, needHandleElements);
        }

        if (needBlueprint) {
            final File targetFile = new File(getTmpFolder() + PATH_SEPARATOR + "blueprint.xml"); //$NON-NLS-1$

            if(needHandleElements.size() > 0) {
                for(String key:needHandleElements.keySet()) {
                    if ("propertyPlaceholder".equals(key)) {
                        collectRouteInfo.put(key, needHandleElements.get(key).asXML());
                    }
                }
            }
            
            TemplateProcessor.processTemplate("ROUTE_BLUEPRINT_CONFIG", //$NON-NLS-1$
                    collectRouteInfo, targetFile, getClass().getResourceAsStream(TEMPLATE_BLUEPRINT_ROUTE), false);
            
            osgiResource.addResource(FileConstants.BLUEPRINT_FOLDER_NAME, targetFile.toURI().toURL());
        }
    }

    @Override
    protected Set<String> getCompiledModuleNames() {
        return super.getCompiledModuleNames();
    }

    @Override
    protected void addOsgiDependencies(Analyzer analyzer, ExportFileResource libResource, ProcessItem processItem)
            throws IOException {

        final DependenciesResolver resolver = new DependenciesResolver(processItem);

        // add manifest items
        // analyzer.setProperty(Analyzer.REQUIRE_BUNDLE, resolver.getManifestRequireBundle(MANIFEST_ITEM_SEPARATOR));
        StringBuilder manifestImportPackage = new StringBuilder();
        /* if (subjobImportPackages != null && subjobImportPackages.containsKey(processItem.getProperty().getId())) {
            // Add subjob import packages
            manifestImportPackage.append(subjobImportPackages.get(processItem.getProperty().getId()));
            manifestImportPackage.append(MANIFEST_ITEM_SEPARATOR);
        } */
        StringBuilder manifestRequireBundle = new StringBuilder();
        if (subjobRequireBundles != null && subjobRequireBundles.containsKey(processItem.getProperty().getId())) {
            // Add subjob require bundles
            manifestRequireBundle.append(subjobRequireBundles.get(processItem.getProperty().getId()));
        }
        analyzer.setProperty(Analyzer.REQUIRE_BUNDLE, manifestRequireBundle.toString());

        //https://jira.talendforge.org/browse/APPINT-33388
        manifestImportPackage.append("org.apache.camel.component.cxf.jaxrs.blueprint;resolution:=optional");
        manifestImportPackage.append(MANIFEST_ITEM_SEPARATOR);
        
        //https://jira.talendforge.org/browse/APPINT-34061
        Set<String> relativePathList = libResource.getRelativePathList();
        search:
            for (String path : relativePathList) {
                Set<URL> resources = libResource.getResourcesByRelativePath(path);
                for (URL url : resources) {
                    String urlStr = url.getPath().replace("\\", "/");
                    if (urlStr.matches("(.*)camel-xslt-alldep-(.*)$")) {
                        manifestImportPackage.append("net.sf.saxon;resolution:=optional");
                        manifestImportPackage.append(MANIFEST_ITEM_SEPARATOR);
                        break search;
                    }
                }
            }

        IDesignerCoreService designerService = RepositoryPlugin.getDefault().getDesignerCoreService();
        IProcess process = designerService.getProcessFromProcessItem(processItem, false);

        for (String lib : process.getNeededLibraries(TalendProcessOptionConstants.MODULES_WITH_CHILDREN)) {
            if (lib != null && lib.matches("camel-jsonpath-(.*)jar")){
                manifestImportPackage.append("org.apache.camel.jsonpath.jackson");
                manifestImportPackage.append(MANIFEST_ITEM_SEPARATOR);
                break;
            }
        }
        
        manifestImportPackage
                .append(resolver.getManifestImportPackage(MANIFEST_ITEM_SEPARATOR))
                .append(",*;resolution:=optional"); //$NON-NLS-1$

        analyzer.setProperty(Analyzer.IMPORT_PACKAGE, manifestImportPackage.toString());
        analyzer.setProperty(Analyzer.EXPORT_PACKAGE, resolver.getManifestExportPackage(MANIFEST_ITEM_SEPARATOR));

        if (GlobalServiceRegister.getDefault().isServiceRegistered(IRunProcessService.class)) {
            IRunProcessService processService = GlobalServiceRegister.getDefault().getService(IRunProcessService.class);
            ITalendProcessJavaProject talendProcessJavaProject = processService.getTalendProcessJavaProject();
            if (talendProcessJavaProject != null) {
                final IPath libPath = talendProcessJavaProject.getLibFolder().getLocation();
                // process external libs
                final List<URL> list = new ArrayList<URL>();
                for (String s : resolver
                        .getManifestBundleClasspath(MANIFEST_ITEM_SEPARATOR)
                        .split(Character.toString(MANIFEST_ITEM_SEPARATOR))) {
                    if (!s.isEmpty()) {
                        list.add(libPath.append(s).toFile().toURI().toURL());
                    }
                }
                libResource.addResources(list);
            }
        }
    }

    @Override
    protected boolean isIncludedLib(ModuleNeeded module) {
        return super.isIncludedLib(module) && !(isProvidedByFeature(module) || isOsgiExcluded(module));
    }

    private boolean isProvidedByFeature(ModuleNeeded module) {
        if (modulesProvidedByFeatures == null) {
            return false;
        }
        String id = module.getId();
        if (id == null) {
            // bean dependency module etc.
            return false;
        }
        return modulesProvidedByFeatures.contains(id);
    }

    private boolean isOsgiExcluded(ModuleNeeded module) {
        Object value = module.getExtraAttributes().get("IS_OSGI_EXCLUDED");
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        }
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return false;
    }

    private void handleSpringXml(String targetFilePath, ProcessItem processItem, InputStream springInput,
            ExportFileResource osgiResource, boolean convertToBP, boolean convertImports,
            Map<String, Element> needHandleElements) {

        File targetFile = new File(getTmpFolder() + PATH_SEPARATOR + targetFilePath);
        try {
            SAXReader saxReader = new SAXReader();
            saxReader.setStripWhitespaceText(false);
            Document document = saxReader.read(springInput);
            Element root = document.getRootElement();

            if (convertToBP) {
                if ("blueprint".equals(root.getName())) {
                    formatSchemaLocation(root, false, false);
                    InputStream inputStream = new ByteArrayInputStream(root.asXML().getBytes());
                    FilesUtils.copyFile(inputStream, targetFile);
                    osgiResource.addResource(FileConstants.BLUEPRINT_FOLDER_NAME, targetFile.toURI().toURL());
                    return;
                }

                //replace spring cxf core to blueprint cxf core.
                Namespace springCxfCoreNs = root.getNamespaceForURI("http://cxf.apache.org/core");
                if (null != springCxfCoreNs) {
                    Namespace blueprintCxfCoreNs = Namespace.get("cxf", "http://cxf.apache.org/blueprint/core");
                    moveNamespace(root, springCxfCoreNs, blueprintCxfCoreNs);
                    root.remove(springCxfCoreNs);
                    root.add(blueprintCxfCoreNs);
                }
                
                String bpPrefix = "bp";
                int cnt = 0;
                while (root.getNamespaceForPrefix(bpPrefix) != null) {
                    bpPrefix = "bp" + (++cnt);
                }
                root.setQName(QName.get("blueprint", bpPrefix, BLUEPRINT_NSURI));
            }

            Namespace springCamelNsp = Namespace.get("camel", CAMEL_SPRING_NSURI);
            Namespace springCamelCxfNsp = Namespace.get("cxf", CAMEL_CXF_NSURI);
            boolean addCamel = springCamelNsp.equals(root.getNamespaceForPrefix("camel"));
            formatSchemaLocation(root, convertToBP, addCamel);

            for (Iterator<?> i = root.elementIterator("import"); i.hasNext();) {
                Element ip = (Element) i.next();
                Attribute resource = ip.attribute("resource");
                URL path = dummyURL(resource.getValue());
                for (ResourceDependencyModel resourceModel : RouteResourceUtil.getResourceDependencies(processItem)) {
                    if (matches(path, resourceModel)) {
                        IFile resourceFile = RouteResourceUtil.getSourceFile(resourceModel.getItem());
                        String cpUrl = adaptedClassPathUrl(resourceModel, convertImports);
                        handleSpringXml(cpUrl, processItem, resourceFile.getContents(), osgiResource, convertImports,
                                convertImports, needHandleElements);
                        resource.setValue(IMPORT_RESOURCE_PREFIX + cpUrl);
                    }
                }
                if (convertImports) {
                    i.remove();
                }
            }

            for (Iterator<?> i = root.elementIterator("bean"); i.hasNext();) {
                Element ip = (Element) i.next();
                Attribute resource = ip.attribute("class");
                if ("org.apache.camel.component.properties.PropertiesComponent".equals(resource.getStringValue())) {
                    // <propertyPlaceholder id="properties" location="classpath:RouteWithSpring.properties"/>
                    Element propertyPlaceholderElement = DocumentHelper.createElement("propertyPlaceholder");
                    propertyPlaceholderElement.addAttribute("id", ip.attribute("id").getStringValue());

                    for (Iterator<?> ii = ip.elementIterator("property"); ii.hasNext();) {
                        Element property = (Element) ii.next();
                        if ("location".equals(property.attribute("name").getStringValue())) {
                            propertyPlaceholderElement.addAttribute("location", property.attribute("value").getStringValue());
                            needHandleElements.put("propertyPlaceholder", propertyPlaceholderElement);
                        }
                    }
                    root.remove(ip);
                }
            }

            if (CONVERT_CAMEL_CONTEXT) {
                if (CONVERT_CAMEL_CONTEXT_ALL) {
                    moveNamespace(root, CAMEL_SPRING_NSURI, CAMEL_BLUEPRINT_NSURI);
                } else {
                    Namespace blueprintCamelNsp = Namespace.get("camel", CAMEL_BLUEPRINT_NSURI);
                    moveNamespace(root, springCamelNsp, blueprintCamelNsp);
                    if (springCamelNsp.equals(root.getNamespaceForPrefix("camel"))) {
                        root.remove(springCamelNsp);
                        root.add(blueprintCamelNsp);
                    }

                    Namespace blueprintCamelCxfNsp = Namespace.get("cxf", CAMEL_BLUEPRINT_CXF_NSURI);
                    moveNamespace(root, springCamelCxfNsp, blueprintCamelCxfNsp);
                    if (springCamelCxfNsp.equals(root.getNamespaceForPrefix("cxf"))) {
                        root.remove(springCamelCxfNsp);
                        root.add(blueprintCamelCxfNsp);
                    }

                    Namespace springCamelDefNsp = Namespace.get(CAMEL_SPRING_NSURI);
                    Namespace blueprintCamelDefNsp = Namespace.get(CAMEL_BLUEPRINT_NSURI);
                    for (Iterator<?> i = root.elementIterator("camelContext"); i.hasNext();) {
                        Element cc = (Element) i.next();
                        if (springCamelDefNsp.equals(cc.getNamespace())) {
                            moveNamespace(cc, springCamelDefNsp, blueprintCamelDefNsp);
                        }
                    }

                    Namespace springBeansNsp = Namespace.get(SPRING_BEANS_NSURI);
                    for (Iterator<?> iter = root.selectNodes("//*[name() = 'ref']").iterator(); iter.hasNext();) {
                        Element ref = (Element) iter.next();
                        if (springBeansNsp.equals(ref.getNamespace())) {
                            Attribute refBean = ref.attribute("bean");
                            if (refBean != null) {
                                // skip sub elements of "constructor-arg" to avoid TESB-31904
                                // as it is not supported by blueprint
                                if (hasParentElement(refBean.getParent(), "constructor-arg")) {
                                    continue;
                                }
                                if (hasParentElementWithProperty(refBean.getParent(), "name", "constraintMappings")) {
                                    continue;
                                }
                                ref.setQName(QName.get(ref.getName(), "bp", BLUEPRINT_NSURI));
                                ref.addAttribute("component-id", refBean.getValue());
                                ref.remove(refBean);
                            }
                        }
                    }

                }
            }

            InputStream inputStream = new ByteArrayInputStream(root.asXML().getBytes());
            FilesUtils.copyFile(inputStream, targetFile);
            osgiResource.addResource(adaptedResourceFolderName(targetFilePath, convertToBP), targetFile.toURI().toURL());
        } catch (Exception e) {
            Logger.getAnonymousLogger().log(Level.WARNING, "Custom Spring to OSGi conversion failed. ", e);
        } finally {
            try {
                springInput.close();
            } catch (IOException e) {
                Logger.getAnonymousLogger().log(Level.WARNING, "Unexpected File closing failure. ", e);
            }
        }
    }
    
    private boolean hasParentElementWithProperty(Element element, String propertyName, String propertyValue) {
        if (element == null || propertyName == null || propertyValue == null)  {
            return false;
        }

        DefaultAttribute attribute = (DefaultAttribute) element.attribute(propertyName);

        if (attribute != null && attribute.getValue().equalsIgnoreCase(propertyValue)) {
            return true;
        }

        return hasParentElementWithProperty(element.getParent(), propertyName, propertyValue);
    }

    private boolean hasParentElement(Element element, String elementName) {
        if (element == null || elementName == null)  {
            return false;
        }

        if (elementName.equalsIgnoreCase(element.getName())) {
            return true;
        }
        return hasParentElement(element.getParent(), elementName);
    }

    private Map<String, Object> collectRouteInfo(ProcessItem processItem, IProcess process) {
        Map<String, Object> routeInfo = new HashMap<String, Object>();

        // route name and class name
        routeInfo.put("name", processItem.getProperty().getLabel()); //$NON-NLS-1$
        String className = getClassName(processItem);
        String idName = className;
        String suffix = getOsgiServiceIdSuffix();
        if (suffix != null && suffix.length() > 0) {
            idName += suffix;
        }
        routeInfo.put("className", className); //$NON-NLS-1$
        routeInfo.put("idName", idName); // $NON-NLS-2$

        boolean useSAM = false;
        boolean useSL = false;
        boolean hasCXFUsernameToken = false;
        boolean hasCXFSamlConsumer = false;
        boolean hasCXFSamlProvider = false;
        boolean hasCXFRSSamlProviderAuthz = false;

        Collection<NodeType> cSOAPs = EmfModelUtils.getComponentsByName(processItem, "cSOAP");
        boolean hasCXFComponent = !cSOAPs.isEmpty();
        cSOAPs.addAll(EmfModelUtils.getComponentsByName(processItem, "cREST"));
        if (!cSOAPs.isEmpty()) {
            Set<String> consumerNodes = new HashSet<String>();
            @SuppressWarnings("unchecked")
            List<ConnectionType> connections = processItem.getProcess().getConnection();
            for (ConnectionType conn : connections) {
                consumerNodes.add(conn.getTarget());
            }

            boolean isEEVersion = isStudioEEVersion();
            for (NodeType node : cSOAPs) {
                boolean nodeUseSAM = false;
                boolean nodeUseSaml = false;
                boolean nodeUseAuthz = false;
                boolean nodeUseRegistry = false;

                // http://jira.talendforge.org/browse/TESB-3850
                String format = EmfModelUtils.computeTextElementValue("DATAFORMAT", node); //$NON-NLS-1$

                if (!useSAM && !"RAW".equals(format)) { //$NON-NLS-1$
                    nodeUseSAM = EmfModelUtils.computeCheckElementValue("ENABLE_SAM", node) //$NON-NLS-1$
                            || EmfModelUtils.computeCheckElementValue("SERVICE_ACTIVITY_MONITOR", node); //$NON-NLS-1$
                }

                // security is disable in case CXF_MESSAGE or RAW dataFormat
                if (!"CXF_MESSAGE".equals(format) && !"RAW".equals(format)) { //$NON-NLS-1$ //$NON-NLS-2$
                    if (isEEVersion && EmfModelUtils.computeCheckElementValue("ENABLE_REGISTRY", node)) { //$NON-NLS-1$
                        nodeUseRegistry = true;
                        // https://jira.talendforge.org/browse/TESB-10725
                        nodeUseSAM = false;
                    } else if (EmfModelUtils.computeCheckElementValue("ENABLE_SECURITY", node)) { //$NON-NLS-1$
                        String securityType = EmfModelUtils.computeTextElementValue("SECURITY_TYPE", node); //$NON-NLS-1$
                        if ("USER".equals(securityType)) { //$NON-NLS-1$
                            hasCXFUsernameToken = true;
                        } else if ("SAML".equals(securityType)) { //$NON-NLS-1$
                            nodeUseSaml = true;
                            nodeUseAuthz =
                                    isEEVersion && EmfModelUtils.computeCheckElementValue("USE_AUTHORIZATION", node);
                        }
                    }
                }
                useSAM |= nodeUseSAM;

                useSL |= EmfModelUtils.computeCheckElementValue("ENABLE_SL", node) //$NON-NLS-1$
                        || EmfModelUtils.computeCheckElementValue("SERVICE_LOCATOR", node);

                if (consumerNodes.contains(ElementParameterParser.getUNIQUENAME(node))) {
                    hasCXFSamlConsumer |= nodeUseSaml | nodeUseRegistry;
                } else {
                    hasCXFSamlProvider |= nodeUseSaml | nodeUseRegistry;
                    hasCXFRSSamlProviderAuthz |= nodeUseAuthz;
                }
            }
        }
        routeInfo.put("useSAM", useSAM); //$NON-NLS-1$
        routeInfo.put("useSL", useSL); //$NON-NLS-1$
        routeInfo.put("hasCXFUsernameToken", hasCXFUsernameToken); //$NON-NLS-1$
        routeInfo.put("hasCXFSamlConsumer", hasCXFSamlConsumer); //$NON-NLS-1$
        routeInfo.put("hasCXFSamlProvider", hasCXFSamlProvider); //$NON-NLS-1$
        routeInfo.put("hasCXFRSSamlProviderAuthz", hasCXFRSSamlProviderAuthz && !hasCXFComponent); //$NON-NLS-1$
        routeInfo.put("hasCXFComponent", hasCXFComponent); //$NON-NLS-1$

        // route OSGi DataSources
        routeInfo.put("dataSources", DataSourceConfig.getAliases(process)); //$NON-NLS-1$

        routeInfo.put("routelets", routelets); //$NON-NLS-1$

        return routeInfo;
    }

    private static boolean matches(URL importURL, ResourceDependencyModel resourceModel) {
        String path = importURL.getPath();
        String refPath = resourceModel.getClassPathUrl();
        if (path == null || path.length() == 0 || refPath == null || refPath.length() == 0) {
            return false;
        }
        if (path.startsWith("/")) {
            return path.length() == refPath.length() + 1 && path.endsWith(refPath);
        }
        return path.equals(refPath);
    }

    private static String adaptedClassPathUrl(ResourceDependencyModel resourceModel, boolean convertToBP) {
        String cpUrl = resourceModel.getClassPathUrl();
        if (convertToBP) {
            return "csi__" + cpUrl.replaceAll("/", "__");
        }
        int insert = cpUrl.lastIndexOf('/') + 1;
        return cpUrl.substring(0, insert) + "sei__" + cpUrl.substring(insert);
    }

    private static String adaptedResourceFolderName(String filePath, boolean convertToBP) {
        if (convertToBP) {
            return FileConstants.BLUEPRINT_FOLDER_NAME;
        }
        int ndx = filePath.lastIndexOf('/');
        return ndx < 0 ? "" : filePath.substring(0, ndx);
    }

    private static void formatSchemaLocation(Element root, boolean addBlueprint, boolean addCamel) {
        Attribute schemaLocation = root.attribute("schemaLocation");
        if (schemaLocation == null) {
            return;
        }
        String value = schemaLocation.getValue().replaceAll("(\\A|\\b)\\s\\s+\\b", "\n            ");
        if (addBlueprint) {
            //see targetNamespace in:
            //http://cxf.apache.org/schemas/blueprint/core.xsd
            //http://cxf.apache.org/schemas/blueprint/policy.xsd
            value = value.replace("http://cxf.apache.org/core", "http://cxf.apache.org/blueprint/core")
                    .replace("http://cxf.apache.org/schemas/core.xsd", "http://cxf.apache.org/schemas/blueprint/core.xsd")
                    .replace("http://cxf.apache.org/schemas/policy.xsd", "http://cxf.apache.org/schemas/blueprint/policy.xsd");
            value += "\n            http://www.osgi.org/xmlns/blueprint/v1.0.0 http://www.osgi.org/xmlns/blueprint/v1.0.0/blueprint.xsd";
        }
        if (addCamel) {
            value += "\n            http://camel.apache.org/schema/blueprint http://camel.apache.org/schema/blueprint/camel-blueprint.xsd";
        }
        schemaLocation.setValue(value);
    }

    private static URL dummyURL(String path) throws MalformedURLException {
        return new URL(null, path, new URLStreamHandler() {

            @Override
            protected URLConnection openConnection(URL u) throws IOException {
                return null;
            }

        });
    }

    private static void moveNamespace(Element treeRoot, Namespace oldNsp, Namespace newNsp) {
        if (treeRoot.getNamespace().equals(oldNsp)) {
            treeRoot.setQName(QName.get(treeRoot.getName(), newNsp));
            treeRoot.remove(oldNsp);
        }
        moveNamespaceInChildren(treeRoot, oldNsp, newNsp);
    }

    private static void moveNamespaceInChildren(Element treeRoot, Namespace oldNsp, Namespace newNsp) {
        for (Iterator<?> i = treeRoot.elementIterator(); i.hasNext();) {
            Element e = (Element) i.next();
            if (e.getNamespace().equals(oldNsp)) {
                e.setQName(QName.get(e.getName(), newNsp));
                e.remove(oldNsp);
            }
            moveNamespaceInChildren(e, oldNsp, newNsp);
        }
    }

    private static void moveNamespace(Element treeRoot, String oldNspURI, String newNspURI) {
        Namespace oldNsp = treeRoot.getNamespace();
        if (oldNspURI.equals(oldNsp.getURI())) {
            Namespace newNsp = Namespace.get(oldNsp.getPrefix(), newNspURI);
            treeRoot.setQName(QName.get(treeRoot.getName(), newNsp));
            treeRoot.remove(oldNsp);
        }
        moveNamespaceInChildren(treeRoot, oldNspURI, newNspURI);
    }

    private static void moveNamespaceInChildren(Element treeRoot, String oldNspURI, String newNspURI) {
        for (Iterator<?> i = treeRoot.elementIterator(); i.hasNext();) {
            Element e = (Element) i.next();
            Namespace oldNsp = e.getNamespace();
            if (oldNspURI.equals(oldNsp.getURI())) {
                Namespace newNsp = Namespace.get(oldNsp.getPrefix(), newNspURI);
                e.setQName(QName.get(e.getName(), newNsp));
                e.remove(oldNsp);
            }
            moveNamespaceInChildren(e, oldNspURI, newNspURI);
        }
    }

    /*
     * private static boolean isAffirmative(String systemProperty) {
     * String option = System.getProperty(systemProperty);
     * if (option == null || option.length() == 0) {
     * return false;
     * }
     * if ("true".equalsIgnoreCase(option)) {
     * return true;
     * }
     * if ("1".equalsIgnoreCase(option)) {
     * return true;
     * }
     * if ("on".equalsIgnoreCase(option)) {
     * return true;
     * }
     * if ("yes".equalsIgnoreCase(option)) {
     * return true;
     * }
     * if ("y".equalsIgnoreCase(option)) {
     * return true;
     * }
     * return false;
     * }
     */

    private static boolean isNotNegated(String systemProperty) {
        String option = System.getProperty(systemProperty);
        if (option == null || option.length() == 0) {
            return true;
        }
        if ("false".equalsIgnoreCase(option)) {
            return false;
        }
        if ("0".equalsIgnoreCase(option)) {
            return false;
        }
        if ("off".equalsIgnoreCase(option)) {
            return false;
        }
        if ("no".equalsIgnoreCase(option)) {
            return false;
        }
        if ("n".equalsIgnoreCase(option)) {
            return false;
        }
        return true;
    }

    private static boolean isAll(String systemProperty) {
        String option = System.getProperty(systemProperty);
        if (option == null || option.length() == 0) {
            return false;
        }
        if ("all".equalsIgnoreCase(option)) {
            return true;
        }
        return false;
    }
}
