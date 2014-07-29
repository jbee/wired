wired
=====

Using mocks is a clear sign of _"doing it wrong"_. However, reality is that 10 out of 10 enterprises have a BIG BALL OF MUD architecture and heaps of mocking tests happily breaking with almost every implementation change while they simultaneously slowly degrade accumulating unneeded mocking and a lack of it that just does fail the particular test done. NPE is forever lurking around the corner to let you know that someone missed something.

The solution, obviously: get rid of the mocking. But enterprises don't think like that or even if: there is a huge pile waiting to be rewritten. You not gonna make it all by yourself in a reasonable time-frame. So things go as they always do in an enterprise: you'll patch it.

The wired container is such a patch that improves the situation...

- makes set-up of mocking test scenarios easier, shorter and more robust
- tells you what you missed to mock
- tells you what you unnecessarily mocked

Its quite simple...
```java
// make yourself a container
Wired container = Wired.container(config);

// wire some mocks
container.wireMock(ThingA.class);
ThingB b = container.wireMock(ThingB.class);

// wire some impl under test
TestedA a = container.wire(TestedA.class);
TestedC c = container.wireStub(new TestedC("Foo"));

// does this make sense?
container.verifyImplementationWiring();

// do the mocking madness (when/then) with a,b,c as before
```
At least the spagetti wiring in setup is now gone. It is more or less a list of the mocked classes and the _real_ implementations under test. You may assign them for later useage or not. Alternativly one can get things back out of the container.

```java
ThingA a = container.get(ThingA.class);
```
Finally there is a `config` that has 3 simple methods to implement:
```java
		void init(Wired container) {
		  // run some common wiring
		  // enterprises like to setup DAOs, translations etc.
		}

		<T> T mock(Class<T> type) {
		  // make me a mock please
		  return MyMadness.mockOf(type); 
		}

		boolean isMock(Object mayBeMock) {
      // is this a mock?
      return MyMadness.isMock(mayBeMock); 
		}
```
So the container is independent of the mocking madness you prefer to get bitten by. If you read so far I assume you are working in one of those enterprises - let me say a last thing: Good luck, you'll need it.
