ServerEvents.loaded(event => {
  try {
    const Fixture = Java.loadClass('local.survival.schematicsafetytest.FixtureSwap');
    const mode = JsonIO.read('kubejs/test-mode.json');
    const report = JSON.parse(String(Fixture.run(event.server.overworld(), !!mode.patched)));
    JsonIO.write('kubejs/exported/schematic-safety-result.json', report);
    console.info('[Schematic Safety Selftest] '+JSON.stringify(report));
  } finally {
    event.server.runCommandSilent('stop');
  }
});
