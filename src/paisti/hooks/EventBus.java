package paisti.hooks;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Iterables;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.function.Consumer;

@ThreadSafe
public class EventBus {
    private volatile ImmutableMultimap<Class<?>, Subscriber> subscribers = ImmutableMultimap.of();
    private final Consumer<Throwable> exceptionHandler;

    public EventBus(@Nonnull Consumer<Throwable> exceptionHandler)
    {
	this.exceptionHandler = exceptionHandler;
    }

    public EventBus()
    {
	this(System.err::println);
    }

    /**
     * Posts provided event to all registered subscribers. Subscriber calls are invoked immediately,
     * ordered by priority then their declaring class' name.
     *
     * @param event event to post
     */
    public void post(@Nonnull final Object event)
    {
	for (final Subscriber subscriber : subscribers.get(event.getClass()))
	{
	    try
	    {
		subscriber.invoke(event);
	    }
	    catch (Exception e)
	    {
		exceptionHandler.accept(e);
	    }
	}
    }

    public synchronized <T> Subscriber register(Class<T> clazz, Consumer<T> subFn, int priority)
    {
	final ImmutableMultimap.Builder<Class<?>, Subscriber> builder = ImmutableMultimap.builder();
	builder.putAll(subscribers);
	builder.orderValuesBy(Comparator.comparingInt(Subscriber::getPriority).reversed()
	    .thenComparing(s -> s.object.getClass().getName()));

	@SuppressWarnings("unchecked")
	Subscriber sub = new Subscriber(subFn, null, priority, (Consumer<Object>) subFn);
	builder.put(clazz, sub);

	subscribers = builder.build();

	return sub;
    }

    /**
     * Unregisters all subscribed methods from provided subscriber object.
     *
     * @param object object to unsubscribe from
     */
    public synchronized void unregister(@Nonnull final Object object)
    {
	subscribers = ImmutableMultimap.copyOf(Iterables.filter(
	    subscribers.entries(),
	    e -> e.getValue().getObject() != object
	));
    }

    public synchronized void unregister(Subscriber sub)
    {
	if (sub == null)
	{
	    return;
	}

	subscribers = ImmutableMultimap.copyOf(Iterables.filter(
	    subscribers.entries(),
	    e -> sub != e.getValue()
	));
    }

    public static class Subscriber {
	private final Object object;
	private final Method method;
	private final int priority;
	private final Consumer<Object> lambda;

	public Subscriber(Object object, Method method, int priority, Consumer<Object> lambda) {
	    this.object = object;
	    this.method = method;
	    this.priority = priority;
	    this.lambda = lambda;
	}

	void invoke(Object arg) {
	    if (lambda != null) {
		lambda.accept(arg);
	    } else {
		try {
		    method.invoke(object, arg);
		} catch (Exception e) {
		    e.printStackTrace();
		}
	    }
	}

	public Object getObject() {
	    return object;
	}

	public Method getMethod() {
	    return method;
	}

	public int getPriority() {
	    return priority;
	}

	public Consumer<Object> getLambda() {
	    return lambda;
	}
    }
}
