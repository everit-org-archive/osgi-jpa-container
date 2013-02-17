package org.everit.osgi.jpa.container;

/*
 * Copyright (c) 2011, Everit Kft.
 *
 * All rights reserved.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA 02110-1301  USA
 */

import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.persistence.SharedCacheMode;
import javax.persistence.ValidationMode;
import javax.persistence.spi.ClassTransformer;
import javax.persistence.spi.PersistenceUnitInfo;
import javax.persistence.spi.PersistenceUnitTransactionType;
import javax.sql.DataSource;

import org.apache.aries.jpa.container.parsing.ParsedPersistenceUnit;
import org.apache.aries.jpa.container.unit.impl.TempBundleDelegatingClassLoader;
import org.apache.aries.util.AriesFrameworkUtil;
import org.osgi.framework.ServiceReference;

public class PersistenceUnitInfoImpl implements PersistenceUnitInfo {

    private static final Logger LOGGER = LoggerFactory.getLogger(PersistenceUnitInfoImpl.class);

    private final ParsedPersistenceUnit parsedPersistenceUnit;

    private final ServiceReference providerRef;

    private final DataSource jtaDataSource;

    private final DataSource nonJtaDataSource;

    private final List<URL> jarFileUrls;

    private final List<String> managedClassNames;

    /**
     * Lazily initialized classloader of this persistence unit.
     */
    private final AtomicReference<ClassLoader> cl = new AtomicReference<ClassLoader>();

    /**
     * Creating a new persistence unit info.
     * 
     * @param parsedPersistenceUnit
     *            The parsed persistence unit that should this info class implements.
     * @param jtaDataSource
     *            The jta datasource if available.
     * @param nonJtaDataSource
     *            The non-jta datasource if available.
     */
    public PersistenceUnitInfoImpl(final ParsedPersistenceUnit parsedPersistenceUnit,
            final ServiceReference providerRef, final DataSource jtaDataSource,
            final DataSource nonJtaDataSource) {
        this.providerRef = providerRef;
        this.parsedPersistenceUnit = parsedPersistenceUnit;
        this.jtaDataSource = jtaDataSource;
        this.nonJtaDataSource = nonJtaDataSource;
        jarFileUrls = deriveJarFileUrls();
        managedClassNames = deriveManagedClassNames();
    }

    @Override
    public void addTransformer(final ClassTransformer pTransformer) {
        // TODO add transformer support that will be built into the classloader retrieved by getClassLoader function.
    }

    public List<URL> deriveJarFileUrls() {
        List<String> jarFiles = (List<String>) parsedPersistenceUnit.getPersistenceXmlMetadata().get(
                ParsedPersistenceUnit.JAR_FILES);
        List<URL> urls = new ArrayList<URL>();
        if (jarFiles != null) {
            for (String jarFile : jarFiles) {
                URL url = parsedPersistenceUnit.getDefiningBundle().getResource(jarFile);
                if (url == null) {
                    LOGGER.error("Could not find jar file for persistence unit creation at " + url);
                } else {
                    urls.add(url);
                }
            }
        }
        // TODO Handle Jar files with Absolute path
        return urls;
    }

    private List<String> deriveManagedClassNames() {
        List<String> mcList = new ArrayList<String>((List<String>) parsedPersistenceUnit.getPersistenceXmlMetadata()
                .get(ParsedPersistenceUnit.MANAGED_CLASSES));
        boolean excludeUnlistedClasses = internalExcludeUnlistedClasses();
        if (!excludeUnlistedClasses) {
            // TODO Read classes from jar files that were provided
            // TODO Read classes from the current bundle
        }
        return mcList;
    }

    /**
     * As we collect every classes in this persistence unit that may be necessary we do not want the persistence
     * provider to do any annotation scanning. For more information please see
     * {@link PersistenceUnitInfo#excludeUnlistedClasses()}
     * 
     * @return Always true.
     */
    @Override
    public boolean excludeUnlistedClasses() {
        return true;
    }

    @Override
    public ClassLoader getClassLoader() {
        if (cl.get() == null) {
            // use forced because for even for a resolved bundle we could otherwise get null
            cl.compareAndSet(null, AriesFrameworkUtil.getClassLoaderForced(parsedPersistenceUnit.getDefiningBundle()));
        }

        return cl.get();
    }

    @Override
    public List<URL> getJarFileUrls() {
        return jarFileUrls;
    }

    @Override
    public DataSource getJtaDataSource() {
        return jtaDataSource;
    }

    @Override
    public List<String> getManagedClassNames() {
        return managedClassNames;
    }

    @Override
    public List<String> getMappingFileNames() {
        List<String> mappingFiles = (List<String>) parsedPersistenceUnit.getPersistenceXmlMetadata().get(
                ParsedPersistenceUnit.MAPPING_FILES);
        if (mappingFiles == null) {
            mappingFiles = new ArrayList<String>();
        }

        return Collections.unmodifiableList(mappingFiles);
    }

    @Override
    public ClassLoader getNewTempClassLoader() {
        ClassLoader providerClassLoader = AriesFrameworkUtil.getClassLoader(providerRef.getBundle());
        return new TempBundleDelegatingClassLoader(parsedPersistenceUnit.getDefiningBundle(), providerClassLoader);
    }

    @Override
    public DataSource getNonJtaDataSource() {
        return nonJtaDataSource;
    }

    @Override
    public String getPersistenceProviderClassName() {
        return (String) parsedPersistenceUnit.getPersistenceXmlMetadata().get(ParsedPersistenceUnit.PROVIDER_CLASSNAME);
    }

    @Override
    public String getPersistenceUnitName() {
        return (String) parsedPersistenceUnit.getPersistenceXmlMetadata().get(ParsedPersistenceUnit.UNIT_NAME);
    }

    @Override
    public URL getPersistenceUnitRootUrl() {
        return parsedPersistenceUnit.getDefiningBundle().getResource("/");
    }

    @Override
    public String getPersistenceXMLSchemaVersion() {
        return (String) parsedPersistenceUnit.getPersistenceXmlMetadata().get(ParsedPersistenceUnit.SCHEMA_VERSION);
    }

    @Override
    public Properties getProperties() {
        return (Properties) parsedPersistenceUnit.getPersistenceXmlMetadata().get(ParsedPersistenceUnit.PROPERTIES);
    }

    @Override
    public SharedCacheMode getSharedCacheMode() {
        String s = (String) parsedPersistenceUnit.getPersistenceXmlMetadata().get(
                ParsedPersistenceUnit.SHARED_CACHE_MODE);

        if (s == null) {
            return SharedCacheMode.UNSPECIFIED;
        } else {
            return SharedCacheMode.valueOf(s);
        }
    }

    @Override
    public PersistenceUnitTransactionType getTransactionType() {
        String s = (String) parsedPersistenceUnit.getPersistenceXmlMetadata().get(
                ParsedPersistenceUnit.TRANSACTION_TYPE);

        if (s == null) {
            return PersistenceUnitTransactionType.JTA;

        } else {
            return PersistenceUnitTransactionType.valueOf(s);
        }
    }

    @Override
    public ValidationMode getValidationMode() {
        String s = (String) parsedPersistenceUnit.getPersistenceXmlMetadata()
                .get(ParsedPersistenceUnit.VALIDATION_MODE);

        if (s == null) {
            return ValidationMode.AUTO;
        } else {
            return ValidationMode.valueOf(s);
        }
    }

    /**
     * Used to check whether we want to use only the classes in the list of persistence.xml or we want to do annotation
     * scanning also.
     * 
     * @return whether to exclude unlisted classes or not.
     */
    public boolean internalExcludeUnlistedClasses() {
        Boolean result = (Boolean) parsedPersistenceUnit.getPersistenceXmlMetadata().get(
                ParsedPersistenceUnit.EXCLUDE_UNLISTED_CLASSES);
        return (result == null) ? false : result;
    }

}
