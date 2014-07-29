wired
=====

Using mocks is a clear sign of _"doing it wrong"_. However, reality is that 10 out of 10 enterprises have a BIG BALL OF MUD architecture and heaps of mocking tests happily breaking with almost every implementation change while they simultaneously slowly degrade accumulating unneeded mocking and a lack of it that just does fail the particular test done. NPE is forever lurking around the corner to let you know that someone missed something.

The solution, obviously: get rid of the mocking. But enterprises don't think like that or even if: there is a huge pile waiting to be rewritten. You not gonna make it all by yourself in a reasonable time-frame. So things go as they always do in an enterprise: you'll patch it.

The wired container is such a patch that improves the situation...

- makes set-up of mocking test scenarios easier, shorter and more robust
- tells you what you missed to mock
- tells you what you unnecessarily mocked

