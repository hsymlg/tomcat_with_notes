/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.catalina.startup;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

/**
 * <p>
 * 用于为Catalina创建类加载器的工具类。工厂方法需要以下参数来构建新的类加载器（所有情况均有合适的默认值）：
 * </p>
 * <ul>
 * <li>一组包含未打包类（和资源）的目录，这些目录应包含在类加载器的仓库中。</li>
 * <li>一组包含JAR文件的目录。在这些目录中发现的每个可读JAR文件都将添加到类加载器的仓库中。</li>
 * <li>应成为新类加载器父类加载器的{@code ClassLoader}实例。</li>
 * </ul>
 *
 * @author Craig R. McClanahan
 */

/**
 * 加载器名称	层级	职责	父加载器
 * Bootstrap	根层级	同 Java 标准	无
 * Extension	第二层	同 Java 标准	Bootstrap
 * Application	第三层	同 Java 标准，加载 JVM 启动时的系统类路径	Extension
 * 下面2层是Tomcat的类加载器层级：
 * CommonClassLoader	第四层	加载 Tomcat 公共库（${catalina.base}/lib），供所有 Web 应用共享	Application
 * WebappClassLoader	第五层	加载单个 Web 应用的类库（WEB-INF/classes和WEB-INF/lib），实现应用隔离	CommonClassLoader
 */

/**
 * 工厂模式简介
 * 工厂模式是一种创建型设计模式，其核心思想是将对象的创建过程封装在一个专门的工厂类中，客户端无需直接通过new操作符创建对象，而是通过工厂类提供的方法来获取所需对象。这种模式有以下几个关键优点：
 *
 * 封装创建逻辑：将对象创建的复杂逻辑封装在工厂类中，客户端无需关心具体实现
 * 解耦：客户端与具体产品类解耦，只需依赖工厂接口
 * 符合开闭原则：新增产品时只需扩展工厂类，无需修改现有代码
 * 统一创建接口：为不同类型的产品提供统一的创建接口
 *
 * 工厂模式通常分为三种类型：简单工厂模式、工厂方法模式和抽象工厂模式。
 *
 * ClassLoaderFactory类是工厂模式在实际代码中的典型应用，它通过静态工厂方法创建类加载器实例，完全符合工厂模式的核心特征。
 * 用final修饰确保该类不能被继承，体现了工厂类的封装性
 *
 * 提供了两个重载的工厂方法（createClassLoader）用于创建类加载器
 * 工厂方法内部封装了类加载器的完整创建过程
 * 包括类路径的构建、URL 的处理、安全特权操作等复杂逻辑
 * 最终通过URLClassLoader创建具体的类加载器实例
 *
 * 使用该工厂类的客户端代码只需调用工厂方法：
 * ClassLoaderFactory.createClassLoader(unpacked.toArray(new File[0]),
 *                     packed.toArray(new File[0]), null);
 * 客户端无需知道URLClassLoader的具体创建细节
 * 无需直接使用new操作符创建类加载器
 * 客户端只依赖ClassLoader接口，与具体实现类解耦
 */
public final class ClassLoaderFactory {

    // 使用LogFactory获取日志记录器，用于记录类加载器创建过程中的信息
    private static final Log log = LogFactory.getLog(ClassLoaderFactory.class);

    // --------------------------------------------------------- Public Methods

    /**
     * 基于配置默认值和指定的目录路径创建并返回一个新的类加载器：
     *
     * @param unpacked 包含未打包类的目录路径数组，将被添加到类加载器的仓库中；
     *                 若为{@code null}，则不考虑任何未打包目录
     * @param packed   包含JAR文件的目录路径数组，其中的JAR文件将被添加到类加载器的仓库中；
     *                 若为{@code null}，则不考虑任何JAR文件目录
     * @param parent   新类加载器的父类加载器；若为{@code null}，则使用系统类加载器
     *
     * @return 新创建的类加载器
     *
     * @exception Exception 构建类加载器时发生错误
     */
    public static ClassLoader createClassLoader(File[] unpacked, File[] packed, final ClassLoader parent)
        throws Exception {

        // 记录调试信息，表明正在创建新的类加载器
        if (log.isDebugEnabled()) {
            log.debug("Creating new class loader");
        }

        // 构建此类加载器的"类路径"，使用LinkedHashSet确保元素唯一且保持添加顺序
        Set<URL> set = new LinkedHashSet<>();

        // 添加未打包的目录（包含.class文件的目录）
        if (unpacked != null) {
            for (File file : unpacked) {
                // 检查文件是否可读，不可读则跳过
                if (!file.canRead()) {
                    continue;
                }
                // 获取规范路径，解决符号链接等问题
                file = new File(file.getCanonicalPath());
                // 将文件转换为URL格式
                URL url = file.toURI().toURL();
                // 记录调试信息，表明已包含该目录
                if (log.isDebugEnabled()) {
                    log.debug("  Including directory " + url);
                }
                // 将目录URL添加到类路径集合中
                set.add(url);
            }
        }

        // 添加打包目录中的JAR文件
        if (packed != null) {
            for (File directory : packed) {
                // 检查是否为可读目录，否则跳过
                if (!directory.isDirectory() || !directory.canRead()) {
                    continue;
                }
                // 获取目录下的所有文件和文件夹
                String[] filenames = directory.list();
                if (filenames == null) {
                    continue;
                }
                // 遍历目录下的所有文件
                for (String s : filenames) {
                    // 转换为小写进行检查，确保不区分大小写
                    String filename = s.toLowerCase(Locale.ENGLISH);
                    // 只处理.jar后缀的文件
                    if (!filename.endsWith(".jar")) {
                        continue;
                    }
                    // 构建完整的JAR文件路径
                    File file = new File(directory, s);
                    // 记录调试信息，表明已包含该JAR文件
                    if (log.isDebugEnabled()) {
                        log.debug("  Including jar file " + file.getAbsolutePath());
                    }
                    // 将JAR文件转换为URL格式并添加到类路径集合中
                    URL url = file.toURI().toURL();
                    set.add(url);
                }
            }
        }

        // 构建类加载器本身
        final URL[] array = set.toArray(new URL[0]);
        // 使用特权操作创建类加载器，确保在安全管理器环境下有足够权限
        return AccessController.doPrivileged((PrivilegedAction<URLClassLoader>) () -> {
            if (parent == null) {
                // 若无父类加载器，创建根类加载器
                return new URLClassLoader(array);
            } else {
                // 否则创建具有指定父类加载器的类加载器
                return new URLClassLoader(array, parent);
            }
        });
    }

    /**
     * 基于配置默认值和指定的仓库列表创建并返回一个新的类加载器：
     *
     * @param repositories 类目录、JAR文件、JAR目录或URL的列表，应添加到类加载器的仓库中
     * @param parent       新类加载器的父类加载器；若为{@code null}，则使用系统类加载器
     *
     * @return 新创建的类加载器
     *
     * @exception Exception 构建类加载器时发生错误
     */
    public static ClassLoader createClassLoader(List<Repository> repositories, final ClassLoader parent)
        throws Exception {

        // 记录调试信息，表明正在创建新的类加载器
        if (log.isDebugEnabled()) {
            log.debug("Creating new class loader");
        }

        // 构建此类加载器的"类路径"，使用LinkedHashSet确保元素唯一且保持添加顺序
        Set<URL> set = new LinkedHashSet<>();

        // 处理仓库列表
        if (repositories != null) {
            for (Repository repository : repositories) {
                // 根据仓库类型进行不同处理
                if (repository.getType() == RepositoryType.URL) {
                    // 处理URL类型的仓库
                    URL url = buildClassLoaderUrl(repository.getLocation());
                    if (log.isDebugEnabled()) {
                        log.debug("  Including URL " + url);
                    }
                    set.add(url);
                } else if (repository.getType() == RepositoryType.DIR) {
                    // 处理目录类型的仓库
                    File directory = new File(repository.getLocation());
                    directory = directory.getCanonicalFile();
                    if (!validateFile(directory, RepositoryType.DIR)) {
                        continue;
                    }
                    URL url = buildClassLoaderUrl(directory);
                    if (log.isDebugEnabled()) {
                        log.debug("  Including directory " + url);
                    }
                    set.add(url);
                } else if (repository.getType() == RepositoryType.JAR) {
                    // 处理JAR文件类型的仓库
                    File file = new File(repository.getLocation());
                    file = file.getCanonicalFile();
                    if (!validateFile(file, RepositoryType.JAR)) {
                        continue;
                    }
                    URL url = buildClassLoaderUrl(file);
                    if (log.isDebugEnabled()) {
                        log.debug("  Including jar file " + url);
                    }
                    set.add(url);
                } else if (repository.getType() == RepositoryType.GLOB) {
                    // 处理通配符类型的仓库（包含多个JAR文件的目录）
                    File directory = new File(repository.getLocation());
                    directory = directory.getCanonicalFile();
                    if (!validateFile(directory, RepositoryType.GLOB)) {
                        continue;
                    }
                    if (log.isDebugEnabled()) {
                        log.debug("  Including directory glob " + directory.getAbsolutePath());
                    }
                    // 获取目录下的所有文件和文件夹
                    String[] filenames = directory.list();
                    if (filenames == null) {
                        continue;
                    }
                    // 遍历目录下的所有文件，处理JAR文件
                    for (String s : filenames) {
                        String filename = s.toLowerCase(Locale.ENGLISH);
                        if (!filename.endsWith(".jar")) {
                            continue;
                        }
                        File file = new File(directory, s);
                        file = file.getCanonicalFile();
                        if (!validateFile(file, RepositoryType.JAR)) {
                            continue;
                        }
                        if (log.isDebugEnabled()) {
                            log.debug("    Including glob jar file " + file.getAbsolutePath());
                        }
                        URL url = buildClassLoaderUrl(file);
                        set.add(url);
                    }
                }
            }
        }

        // 构建类加载器本身
        final URL[] array = set.toArray(new URL[0]);
        // 记录跟踪信息，列出所有添加到类加载器的位置
        if (log.isTraceEnabled()) {
            for (int i = 0; i < array.length; i++) {
                log.trace("  location " + i + " is " + array[i]);
            }
        }

        // 使用特权操作创建类加载器，确保在安全管理器环境下有足够权限
        return AccessController.doPrivileged((PrivilegedAction<URLClassLoader>) () -> {
            if (parent == null) {
                // 若无父类加载器，创建根类加载器
                // 当parent = null时，URLClassLoader的构造函数会将父加载器默认设为系统类加载器（Application）（Java 源码规定）。
                // 代码中ClassLoaderFactory.createClassLoader最终会创建一个URLClassLoader，其父加载器为Application ClassLoader。
                return new URLClassLoader(array);
            } else {
                // 否则创建具有指定父类加载器的类加载器
                return new URLClassLoader(array, parent);
            }
        });
    }

    /**
     * 验证文件是否符合指定的仓库类型要求
     *
     * @param file 要验证的文件
     * @param type 仓库类型
     * @return 如果文件有效则返回true，否则返回false
     * @throws IOException 如果在验证过程中发生I/O错误
     */
    private static boolean validateFile(File file, RepositoryType type) throws IOException {
        if (RepositoryType.DIR == type || RepositoryType.GLOB == type) {
            // 验证目录类型的仓库
            if (!file.isDirectory() || !file.canRead()) {
                // 记录目录问题信息
                String msg = "Problem with directory [" + file + "], exists: [" + file.exists() + "], isDirectory: [" +
                    file.isDirectory() + "], canRead: [" + file.canRead() + "]";

                // 获取Catalina主目录和基础目录
                File home = new File(Bootstrap.getCatalinaHome());
                home = home.getCanonicalFile();
                File base = new File(Bootstrap.getCatalinaBase());
                base = base.getCanonicalFile();
                File defaultValue = new File(base, "lib");

                // ${catalina.base}/lib目录是可选的。
                // 当Tomcat使用单独的catalina.home和catalina.base运行且该目录不存在时，隐藏警告。
                if (!home.getPath().equals(base.getPath()) && file.getPath().equals(defaultValue.getPath()) &&
                    !file.exists()) {
                    log.debug(msg);
                } else {
                    log.warn(msg);
                }
                return false;
            }
        } else if (RepositoryType.JAR == type) {
            // 验证JAR文件类型的仓库
            if (!file.canRead()) {
                log.warn("Problem with JAR file [" + file + "], exists: [" + file.exists() + "], canRead: [" +
                    file.canRead() + "]");
                return false;
            }
        }
        return true;
    }

    /*
     * 这两个方法理想情况下应位于工具类org.apache.tomcat.util.buf.UriUtil中，
     * 但该类在类加载器构建完成后才可见。
     */

    /**
     * 从URL字符串构建类加载器使用的URL
     * 处理URL中的特殊字符，确保其符合类加载器的要求
     *
     * @param urlString 原始URL字符串
     * @return 处理后的URL对象
     * @throws MalformedURLException 如果URL格式不正确
     * @throws URISyntaxException 如果URI语法错误
     */
    private static URL buildClassLoaderUrl(String urlString) throws MalformedURLException, URISyntaxException {
        // 处理类加载器URL中可能包含的特殊字符序列"!/"
        // 如果URL用于构造JAR中资源的URL，需要确保"!/"序列不被错误解析
        String result = urlString.replace("!/", "%21/");
        return new URI(result).toURL();
    }

    /**
     * 从文件构建类加载器使用的URL
     * 处理文件路径中的特殊字符，确保其符合类加载器的要求
     *
     * @param file 要转换的文件
     * @return 处理后的URL对象
     * @throws MalformedURLException 如果URL格式不正确
     * @throws URISyntaxException 如果URI语法错误
     */
    private static URL buildClassLoaderUrl(File file) throws MalformedURLException, URISyntaxException {
        // 处理文件可能是目录或文件的情况
        String fileUrlString = file.toURI().toString();
        // 处理类加载器URL中可能包含的特殊字符序列"!/"
        fileUrlString = fileUrlString.replace("!/", "%21/");
        return new URI(fileUrlString).toURL();
    }

    /**
     * 仓库类型枚举
     */
    public enum RepositoryType {
        DIR,    // 目录类型（包含.class文件）
        GLOB,   // 通配符类型（包含多个JAR文件的目录）
        JAR,    // JAR文件类型
        URL     // URL类型
    }

    /**
     * 表示类加载器仓库的类
     * 包含位置信息和仓库类型
     */
    public static class Repository {
        private final String location;  // 仓库位置（文件路径或URL）
        private final RepositoryType type;  // 仓库类型

        /**
         * 构造仓库对象
         *
         * @param location 仓库位置
         * @param type 仓库类型
         */
        public Repository(String location, RepositoryType type) {
            this.location = location;
            this.type = type;
        }

        /**
         * 获取仓库位置
         *
         * @return 仓库位置字符串
         */
        public String getLocation() {
            return location;
        }

        /**
         * 获取仓库类型
         *
         * @return 仓库类型枚举值
         */
        public RepositoryType getType() {
            return type;
        }
    }
}