package snippet;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ehcache.jsr107.EhcacheCachingProvider;
import org.hibernate.SessionFactory;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import org.hibernate.annotations.Immutable;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataBuilder;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.boot.registry.classloading.internal.ClassLoaderServiceImpl;
import org.hibernate.boot.registry.classloading.spi.ClassLoaderService;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.jpa.SpecHints;
import org.hibernate.service.ServiceRegistry;

import com.zaxxer.hikari.HikariDataSource;

import jakarta.persistence.Basic;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityGraph;
import jakarta.persistence.EntityManager;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.metamodel.Attribute;

public class TestCache2 {

    protected static final Logger LOG4J_LOGGER = getLogger();

    private static Logger getLogger() {
        System.setProperty("log4j2.configurationFile", "snippet/AaaTestLog4j.xml");
        return LogManager.getLogger();
    }

    private static String jdbcUrl;

    private static String user;

    private static String password;

    @Entity
    @Immutable
    public static class Book {

        public Book() {}

        public Book(final long id, final String name, final Set<Language> bookLanguages) {
            this.id = id;
            this.name = name;
            this.bookLanguages = bookLanguages;
        }

        @Id
        private long id;

        @Basic
        private String name;

        @ManyToMany
        @JoinTable(
                name = "b2l",
                joinColumns = @JoinColumn(name = "bookid"),
                inverseJoinColumns = @JoinColumn(name = "langid"))
        private Set<Language> bookLanguages;

        public long getId() {
            return id;
        }

        public void setId(final long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(final String name) {
            this.name = name;
        }

        public Set<Language> getBookLanguages() {
            return bookLanguages;
        }

        public void setBookLanguages(final Set<Language> bookLanguages) {
            this.bookLanguages = bookLanguages;
        }
    }

    @Entity
    @Immutable
    @jakarta.persistence.Cacheable
    @org.hibernate.annotations.Cache(usage = CacheConcurrencyStrategy.READ_ONLY)
    public static class Language {

        public Language() {}

        public Language(final long id, final String code, final String name) {
            this.id = id;
            this.code = code;
            this.name = name;
        }

        @Id
        private long id;

        @Basic(fetch = FetchType.LAZY)
        private String code;

        @Basic(fetch = FetchType.LAZY)
        private String name;

        public long getId() {
            return id;
        }

        public void setId(final long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(final String name) {
            this.name = name;
        }

        public String getCode() {
            return code;
        }

        public void setCode(final String code) {
            this.code = code;
        }
    }

    public static void main(final String[] args) throws Exception {
        LOG4J_LOGGER.info("my info");

        initLocalDbDetails();
        final HikariDataSource dataSource = exampleOpenDataSource(jdbcUrl, user, password, 0);
        dataSource.getConnection().close();
        LOG4J_LOGGER.info("connected to: " + dataSource.getJdbcUrl());

        final MetadataSources sources = bootstrapSources(dataSource);

        // SF close also stops the disk cache thread
        try (final SessionFactory sessionFactory = makeSessionFactory(sources)) {
            // we intentionally create a new EM every time
            populateDb(sessionFactory);
            invalidateCache(sessionFactory);
            loadAllLanguages(sessionFactory);
            findLangs(sessionFactory);
            loadAllBooks(sessionFactory);
        }
        LOG4J_LOGGER.info("SF closed");
    }

    private static void invalidateCache(final SessionFactory sessionFactory) {
        LOG4J_LOGGER.info("Invalidating cache");
        sessionFactory.getCache().evictAll();
    }

    private static void loadAllBooks(final SessionFactory sessionFactory) {
        LOG4J_LOGGER.info("loading books");
        final EntityManager em = sessionFactory.createEntityManager();
        try {
            em.getTransaction().begin();
            final List<Book> allBooks = loadAllObjects(em, Book.class, false);
            LOG4J_LOGGER.info("loaded books: " + allBooks.size());
            for (final Book book : allBooks) {
                final Set<Language> lazyCollection = book.getBookLanguages();
                // Uncomment to load from 2nd-level cache into Session:
                // findLang(em, 1L);
                // findLang(em, 2L);
                // findLang(em, 3L);

                // good:
                // select bl1_0."bookid",bl1_1."id" from "b2l" bl1_0 join "TestCache2$Language"
                // bl1_1 on bl1_1."id"=bl1_0."langid" where bl1_0."bookid"=?
                lazyCollection.size();
                for (final Language lang : lazyCollection) {
                    final long id = lang.getId();
                    // bad:
                    // select l1_0."code",l1_0."name" from
                    // "TestCache2$Language" l1_0 where l1_0."id"=?
                    lang.getCode();
                }
            }
            em.getTransaction().commit();
        } finally {
            em.close();
        }
    }

    private static void findLangs(final SessionFactory sessionFactory) {
        LOG4J_LOGGER.info("finding langs by id (presumably in cache)");
        final EntityManager em = sessionFactory.createEntityManager();
        try {
            em.getTransaction().begin();
            findLang(em, 1L);
            findLang(em, 2L);
            findLang(em, 3L);
            em.getTransaction().commit();
        } finally {
            em.close();
        }
    }

    private static Language findLang(final EntityManager em, final long id) {
        final Language res = em.find(Language.class, id);
        res.getCode();
        return res;
    }

    private static void loadAllLanguages(final SessionFactory sessionFactory) {
        LOG4J_LOGGER.info("loading langs");
        final EntityManager em = sessionFactory.createEntityManager();
        try {
            em.getTransaction().begin();
            final List<Language> allLangs = loadAllObjects(em, Language.class, true);
            LOG4J_LOGGER.info("loaded langs: " + allLangs.size());
            em.getTransaction().commit();
        } finally {
            em.close();
        }
    }

    private static void populateDb(final SessionFactory sessionFactory) {
        LOG4J_LOGGER.info("populating DB");
        final EntityManager em = sessionFactory.createEntityManager();
        try {
            em.getTransaction().begin();
            final Language lang1 = persistNewLanguage(em, 1L, "SQ", "Albanian");
            final Language lang2 = persistNewLanguage(em, 2L, "AR", "Arabic");
            final Language lang3 = persistNewLanguage(em, 3L, "HY", "Armenian");

            em.persist(new Book(1L, "World Atlas 1", new HashSet<>(Set.of(lang1, lang2))));
            em.persist(new Book(2L, "World Atlas 2", new HashSet<>(Set.of(lang2, lang3))));
            em.getTransaction().commit();
        } finally {
            em.close();
        }
    }

    private static <T> List<T> loadAllObjects2(final EntityManager em, final Class<T> clazz) {
        final EntityGraph<T> entityGraph = getFetchAllPropertiesGraph(em, clazz);

        final String hbmEntityName = em.getMetamodel().entity(clazz).getName();

        return em
            .createQuery("from `" + hbmEntityName + "`", clazz)
            .setHint(SpecHints.HINT_SPEC_LOAD_GRAPH, entityGraph)
            .getResultList();
    }

    private static <T> List<T> loadAllObjects(
            final EntityManager em,
            final Class<T> clazz,
            final boolean eager) {
        final EntityGraph<T> entityGraph = getFetchAllPropertiesGraph(em, clazz);

        final CriteriaBuilder cb = em.getCriteriaBuilder();
        final CriteriaQuery<T> cq = cb.createQuery(clazz);
        final Root<T> rootEntry = cq.from(clazz);
        final CriteriaQuery<T> all = cq.select(rootEntry);
        TypedQuery<T> allQuery = em.createQuery(all);
        if (eager) {
            allQuery = allQuery.setHint(SpecHints.HINT_SPEC_LOAD_GRAPH, entityGraph);
        }
        return allQuery.getResultList();
    }

    private static <T> EntityGraph<T> getFetchAllPropertiesGraph(
            final EntityManager em,
            final Class<T> clazz) {
        final EntityGraph<T> entityGraph = em.createEntityGraph(clazz);

        for (final Attribute<? super T, ?> attr : em.getMetamodel().entity(clazz).getAttributes()) {
            entityGraph.addAttributeNodes(attr.getName());
        }
        return entityGraph;
    }

    private static MetadataSources bootstrapSources(final HikariDataSource dataSource) {
        final ClassLoader classLoader = TestCache2.class.getClassLoader();

        final TimeZone tzUtc = TimeZone.getTimeZone("UTC");

        final ClassLoaderServiceImpl classLoaderService = new ClassLoaderServiceImpl(classLoader);

        final String ehCacheXml = TestCache2.class.getResource("ehcache3.xml").toString();
        final ServiceRegistry standardRegistry =
            new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.ENHANCER_ENABLE_LAZY_INITIALIZATION, true)
                .applySetting(AvailableSettings.GLOBALLY_QUOTED_IDENTIFIERS, true)
                .applySetting(
                    AvailableSettings.HBM2DDL_AUTO,
                    org.hibernate.tool.schema.Action.CREATE_ONLY.getExternalHbm2ddlName())
                .applySetting(AvailableSettings.DATASOURCE, dataSource)
                .applySetting(AvailableSettings.LOG_SLOW_QUERY, Integer.toString(11_000))
                .applySetting(AvailableSettings.JDBC_TIME_ZONE, tzUtc)
                // hibernate.cache.region.factory_class=jcache
                .applySetting(
                    AvailableSettings.CACHE_REGION_FACTORY,
                    org.hibernate.cache.jcache.ConfigSettings.SIMPLE_FACTORY_NAME)
                // hibernate.javax.cache.provider=
                .applySetting(
                    org.hibernate.cache.jcache.ConfigSettings.PROVIDER,
                    EhcacheCachingProvider.class.getName())
                .applySetting(org.hibernate.cache.jcache.ConfigSettings.CONFIG_URI, ehCacheXml)
                .addService(ClassLoaderService.class, classLoaderService)
                .build();

        final MetadataSources sources = new MetadataSources(standardRegistry);

        sources.addAnnotatedClass(Book.class);
        sources.addAnnotatedClass(Language.class);
        return sources;
    }

    private static Language persistNewLanguage(
            final EntityManager em,
            final long id,
            final String code,
            final String name) {
        final Language res = new Language(id, code, name);
        em.persist(res);
        return res;
    }

    private static SessionFactory makeSessionFactory(final MetadataSources sources) {
        final MetadataBuilder metadataBuilder = sources.getMetadataBuilder();

        final Metadata metadata = metadataBuilder.build();
        final SessionFactory sessionFactory = metadata.getSessionFactoryBuilder().build();
        // validateAttributeResolvation(sources, sessionFactory);

        return sessionFactory;
    }

    private static HikariDataSource exampleOpenDataSource(
            final String jdbcUrl,
            final String userName,
            final String password,
            final Integer connPoolKey) throws Exception {
        final HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName(String.format("MxDbConnector-HikaryCP-%d", connPoolKey));
        dataSource.setJdbcUrl(jdbcUrl);
        dataSource.setUsername(userName);
        dataSource.setPassword(password);
        dataSource.setMinimumIdle(0);
        dataSource.setReadOnly(false);

        return dataSource;
    }

    private static void initLocalDbDetails() throws IOException {
        jdbcUrl = getLocalDbJdbcUrl();
        user = "SA";
        password = "";
    }

    private static String getLocalDbJdbcUrl() throws IOException {
        final StringBuilder serverUrl = new StringBuilder();
        serverUrl.append("mem:");
        final String databaseName = "default";
        final String databaseDir = databaseName;
        serverUrl.append(databaseDir).append(";");

        return "jdbc:hsqldb:" + serverUrl.toString();
    }

}
