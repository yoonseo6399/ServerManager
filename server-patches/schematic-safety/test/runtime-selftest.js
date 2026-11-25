ServerEvents.loaded(event => {
  const ItemStack = Java.loadClass('net.minecraft.world.item.ItemStack');
  const BlockPos = Java.loadClass('net.minecraft.core.BlockPos');
  const AllItems = Java.loadClass('com.simibubi.create.AllItems');
  const Components = Java.loadClass('com.simibubi.create.AllDataComponents');
  const SchematicItem = Java.loadClass('com.simibubi.create.content.schematics.SchematicItem');
  const SchematicPrinter = Java.loadClass('com.simibubi.create.content.schematics.SchematicPrinter');
  const FixtureSwap = Java.loadClass('local.survival.schematicsafetytest.FixtureSwap');
  const mode = JsonIO.read('kubejs/test-mode.json');
  const patched = !!mode.patched;
  const report = {patched: patched, checks: [], success: false};
  function record(name, pass, detail) {
    report.checks.push({name:name, pass:!!pass, detail:detail || ''});
    if (!pass) throw new Error(name + ': ' + (detail || 'failed'));
  }
  function stack(name) {
    const value = new ItemStack(AllItems.SCHEMATIC.get());
    value.set(Components.SCHEMATIC_OWNER, 'selftest');
    value.set(Components.SCHEMATIC_FILE, name);
    value.set(Components.SCHEMATIC_DEPLOYED, true);
    value.set(Components.SCHEMATIC_ANCHOR, new BlockPos(0,80,0));
    return value;
  }
  function load(name) { return SchematicItem.loadSchematic(event.server.overworld(), stack(name)); }
  function attempt(name) {
    try { return {threw:false, template:load(name)}; }
    catch(error) { return {threw:true, error:String(error)}; }
  }
  function empty(template) { return template.getSize().getX() === 0 && template.getSize().getY() === 0 && template.getSize().getZ() === 0; }
  try {
    const valid=load('valid.nbt');
    record('valid schematic remains one stone block', valid.getSize().getX() === 1 && valid.getSize().getY() === 1 && valid.getSize().getZ() === 1);
    ['truncated-real.nbt','truncated-nbt.nbt'].forEach(name => {
      const result=attempt(name);
      record(name + ' is contained only when patched', patched ? (!result.threw && empty(result.template)) : result.threw, result.error || 'empty template');
    });
    record('missing file uses existing empty fallback', empty(load('missing.nbt')));
    const before=attempt('repair.nbt');
    record('initial broken repair candidate', patched ? (!before.threw && empty(before.template)) : before.threw, before.error || 'empty template');
    FixtureSwap.restore();
    record('re-uploaded file is loaded without permanent error cache', load('repair.nbt').getSize().getX() === 1);
    let printerThrew=false;let printerError='';
    try { new SchematicPrinter().loadSchematic(stack('truncated-real.nbt'),event.server.overworld(),false); }
    catch(error) { printerThrew=true;printerError=String(error); }
    record('schematic printer path survives bad NBT only when patched', patched ? !printerThrew : printerThrew, printerError);
    const printer = new SchematicPrinter();printer.loadSchematic(stack('valid.nbt'),event.server.overworld(),false);
    record('valid printer remains loaded without errors', printer.isLoaded() && !printer.isErrored());
    report.success=true;
  } catch(error) { report.error=String(error); }
  finally {
    JsonIO.write('kubejs/exported/schematic-safety-result.json',report);
    console.info('[Schematic Safety Selftest] '+JSON.stringify(report));
    event.server.runCommandSilent('stop');
  }
});
