// ============================================================================
//
// Copyright (C) 2006-2018 Talend Inc. - www.talend.com
//
// This source code is available under agreement available at
// %InstallDIR%\features\org.talend.rcp.branding.%PRODUCTNAME%\%PRODUCTNAME%license.txt
//
// You should have received a copy of the agreement
// along with this program; if not, write to Talend SA
// 9 rue Pages 92150 Suresnes, France
//
// ============================================================================
package org.talend.designer.camel.components.localprovider;

import java.io.File;
import java.net.URL;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.Path;
import org.talend.core.model.components.AbstractComponentsProvider;

/**
 * DOC guanglong.du class global comment. Detailled comment
 */
public class CamelLocalComponentsProvider extends AbstractComponentsProvider {

    protected File getExternalComponentsLocation() {
        URL url = FileLocator.find(CamelComponentPlugin.getDefault().getBundle(), new Path("components"), null); //$NON-NLS-1$
        URL fileUrl;
        try {
            fileUrl = FileLocator.toFileURL(url);
            return new File(fileUrl.getPath());
        } catch (Exception e) {
        }
        return null;
    }

}
