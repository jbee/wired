package se.jbee.wired;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

/**
 * A simple testing container to wire mocks using reflection and plug everything
 * into each other as long as the types let us do so.
 *
 * The idea is to let the {@link Wired} container take care of all wiring.
 * As soon as an instance (or type we would like to have an instance of) is
 * introduced to the container it gets itself wired as far as possible but also
 * all other known implementations will be wired with this instance.
 */
public final class Wired {

	/**
	 * Initialises common basis of testing and adapts to whatever mocking is used.
	 */
	public static interface Config {

		/**
		 * Everything that happens during initialisation is not supposed to be
		 * necessarily useful. It is no verification issue when mocks setup
		 * during init aren't used.
		 */
		void init(Wired container);

		/**
		 * Make me a mock please!
		 */
		<T> T mock(Class<T> type);

		/**
		 * Is this a mock?
		 */
		boolean isMock(Object mayBeMock);

	}

	/**
	 * The content of this container
	 */
	private final Map<Class<?>, Object> instances = new IdentityHashMap<Class<?>, Object>();
	/**
	 * Known implementations for interfaces or super-types (mocks will be
	 * contained in {@link #instances} with the interface type as key).
	 */
	private final Map<Class<?>, Class<?>> implementations = new IdentityHashMap<Class<?>, Class<?>>();
	/**
	 * All types that at some point had been injected with an implementation.
	 * This helps to find mocks that are not connected to the object graph
	 * under test.
	 */
	private final Set<Class<?>> injected = new HashSet<Class<?>>();

	private final Config config;

	public static Wired container(Config config) {
		return new Wired(config);
	}

	private Wired(Config config) {
		this.config = config;
		if (config != null) {
			config.init(this);
			for (Class<?> c : implementations.keySet()) {
				injected.add(c); // assume that everything added during init is allowed not to be used so we pretend it is.
			}
		}
	}

	public <T> T wireStub(T bean) {
		if (bean instanceof Class<?>) {
			wire((Class<?>)bean);
			return null;
		}
		registerImplementation(bean);
		wireInstancesWithImplementation(bean);
		return bean;
	}

	public <T> T wireMock(Class<T> mocked) {
		T mock = ensureMockExisits(mocked);
		wireInstancesWithMock(mocked);
		return mock;
	}

	public void wireMocks(Class<?>... mocked) {
		for (Class<?> c : mocked) {
			ensureMockExisits(c);
		}
		for (Class<?> c : mocked) {
			wireInstancesWithMock(c);
		}
	}

	public <T> T wire(Class<T> impl) {
		if (impl.isInterface()) {
			return wireMock(impl);
		}
		T obj = instantiate(impl);
		return wireStub(obj);
	}

	public void wire(Class<?>... impls) {
		for (Class<?> impl : impls) {
			wire(impl);
		}
	}

	public <T> T get(Class<T> bean) {
		T obj = getImplementationFor(bean);
		if (obj == null) {
			throw new IllegalStateException("No instance (or mock) of type `"+bean+"` has been wired so far!");
		}
		return obj;
	}

	public Object[] getAnnotated(Class<? extends Annotation> anno) {
		List<Object> res = new ArrayList<Object>();
		for (Object impl : instances.values()) {
			if (impl.getClass().isAnnotationPresent(anno)) {
				res.add(impl);
			}
		}
		return res.toArray();
	}

	private <T> T getImplementationFor(Class<T> type) {
		@SuppressWarnings("unchecked")
		Class<? extends T> impl = (Class<? extends T>) implementations.get(type);
		if (impl == null) {
			return null;
		}
		return getInstanceFor(impl);
	}

	@SuppressWarnings("unchecked")
	private <T> T getInstanceFor(Class<T> type) {
		return (T) instances.get(type);
	}

	private <T> T ensureMockExisits(Class<T> mock) {
		T obj = getInstanceFor(mock);
		if (obj == null) {
			obj = config.mock(mock);
			registerMock(obj, mock);
		}
		return obj;
	}

	private <T> void register(T obj, Class<? super T> interfaceType, Class<T> impl) {
		instances.put(impl, obj);
		implementations.put(interfaceType, impl);
	}

	private <T> void registerMock(T obj, Class<T> mocked) {
		Class<?> impl = implementations.get(mocked);
		if (impl != null && impl != mocked) {
			throw new IllegalStateException("A mock of type `"+mocked+"` tries to take the role of a already registered implementation: "+impl);
		}
		register(obj, mocked, mocked);
	}

	private <T> void registerImplementation(T obj) {
		Class<?> impl = obj.getClass();
		if (implementations.containsKey(impl)) {
			throw new IllegalStateException("A implementation of type `"+impl+"` has been registered already!");
		}
		instances.put(impl, obj);
		implementations.put(impl, impl);
		for (Class<?> relevantInterface : characteristicInterfacesOf(impl)) {
			implementations.put(relevantInterface, impl);
		}
	}

	private Set<Class<?>> characteristicInterfacesOf(Class<?> impl) {
		Set<Class<?>> characteristic = new HashSet<Class<?>>(Arrays.asList(impl.getInterfaces()));
		// none of the inherited ones...
		Class<?> supertype = impl.getSuperclass();
		while (supertype != null) {
			for (Class<?> i : supertype.getInterfaces()) {
				characteristic.remove(i);
			}
			supertype = supertype.getSuperclass();
		}
		// none of the obvious helper ones...
		for (Class<?> i : new ArrayList<Class<?>>(characteristic)) {
			if (i.getSimpleName().endsWith("Listener")) {
				characteristic.remove(i);
			}
		}
		return characteristic;
	}

	private void wireInstancesWithImplementation(Object impl) {
		for (Method m : impl.getClass().getMethods()) {
			if (isSetter(m)) {
				Object obj = getImplementationFor(m.getParameterTypes()[0]);
				if (obj != null) {
					set(impl, m, obj);
				}
			}
		}
		for (Class<?> i : characteristicInterfacesOf(impl.getClass())) {
			wireInstancesWithType(i);
		}
		wireInstancesWithType(impl.getClass());
	}

	/**
	 * A mock itself is not wired with existing instances (a mock is expected to
	 * be a dead end). All instances (that are not mocks) will get the mock but
	 * the mock is just use for the mocked interface.
	 */
	private void wireInstancesWithMock(Class<?> interfaceType) {
		wireInstancesWithType(interfaceType);
	}

	private void wireInstancesWithType(Class<?> type) {
		Object impl = get(type);
		for (Entry<Class<?>, Object> e : instances.entrySet()) {
			if (!e.getKey().isInterface()) { // interface => mock
				Object obj = e.getValue();
				for (Method m : obj.getClass().getMethods()) {
					if (isSetterForType(m, type)) {
						set(obj, m, impl);
					}
				}
			}
		}
	}

	private void set(Object inst, Method m, Object param) {
		try {
			m.invoke(inst, param);
			injected.add(m.getParameterTypes()[0]);
		} catch (Exception e1) {
			throw new RuntimeException(e1);
		}
	}

	private boolean isSetterForType(Method m, Class<?> interfaceType) {
		return isSetter(m) && m.getParameterTypes()[0] == interfaceType;
	}

	private boolean isSetter(Method m) {
		return m.getName().startsWith("set") && m.getParameterTypes().length == 1 && m.getReturnType() == void.class;
	}

	private <T> T instantiate(Class<T> impl) {
		try {
			return impl.newInstance();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private String name(Class<?> inst) {
		String name = inst.getSimpleName();
		if (name.contains("$")) {
			return "mock of "+name.substring(0, name.indexOf('$'));
		}
		return name;
	}

	/**
	 * It is important to have a useful {@link #toString()} to allow a quick
	 * view on the state of the container during debug.
	 */
	@Override
	public String toString() {
		StringBuilder b = new StringBuilder();
		for (Entry<Class<?>,Object> inst : instances.entrySet()) {
			Object obj = inst.getValue();
			Class<? extends Object> impl = obj.getClass();
			b.append("- ").append(name(inst.getKey())).append(" => ").append(name(impl)).append("\n");
			if (!inst.getKey().isInterface()) {
				Class<?> t = impl;
				while (t != Object.class) {
					for (Field f : t.getDeclaredFields()) {
						if (implementations.containsKey(f.getType())) {
							String value = "null";
							Object field = get(obj, f);
							if (field != null) {
								value = name(field.getClass());
							}
							b.append('\t').append(name(f.getType())).append(" ").append(f.getName()).append(" = ").append(value).append('\n');
						}
					}
					t = t.getSuperclass();
				}
			}
		}
		return b.toString();
	}

	private Object get(Object obj, Field f) {
		try {
			f.setAccessible(true);
			return f.get(obj);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	/*
	 * Verification
	 */

	/**
	 * Checks that all (non mock) implementation have been fully wired, that is
	 * to say they have all dependencies wired with a mock or implementation.
	 *
	 * This will force test using the verification to explicitly mock all
	 * services another service depends upon even if the particular test might
	 * run without those missing. However, the test reflects more honestly how
	 * the tested situation looks like and will not break when previously
	 * irrelevant services become used in the test scenario (as long as a mock
	 * is appropriate).
	 */
	public void verifyImplementationWiring() {
		verifyImplementationsSuppliedWithDependencies();
		verifyMocksAreRequired();
	}

	/**
	 * Are we all there?
	 */
	private void verifyImplementationsSuppliedWithDependencies() {
		for (Entry<Class<?>, Object> e : instances.entrySet()) {
			if (!e.getKey().isInterface() && !config.isMock(e.getValue())) {
				Object inst = e.getValue();
				for (Field f : inst.getClass().getDeclaredFields()) {
					if (!Modifier.isStatic(f.getModifiers()) && !Collection.class.isAssignableFrom(f.getType())) {
						Object value = get(inst, f);
						if (value == null) {
							String impl = name(inst.getClass());
							String mock = f.getType().getSimpleName();
							throw new IllegalStateException(impl+" misses dependency for field "+f.getName()+" of type "+mock+"\n"
									+"It may be the case a dependency has been added to "+impl+" that now simply needs to be mocked via `container.wireMock("+mock+".class)`");
						}
					}
				}
			}
		}
	}

	/**
	 * Someone without an invitation?
	 */
	private void verifyMocksAreRequired() {
		for (Entry<Class<?>, Object> e : instances.entrySet()) {
			if (config.isMock(e.getValue()) && !injected.contains(e.getKey())) {
				throw new IllegalStateException(name(e.getKey())+" is not required but has been mocked.\n"
						+"It mab be that an implementation under test has been refactored so a dependency disappeared. The dependency should also be removed from the test.");
			}
		}
	}

}
