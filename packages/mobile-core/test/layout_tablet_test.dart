import 'package:engenheiro_design/engenheiro_design.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

/// Tablet (10" deitado = 1280 dp) e celular (412 dp): largura de leitura só onde sobra espaço.
void main() {
  Future<Rect> conteudo(WidgetTester tester, Size tela) async {
    tester.view.physicalSize = tela * 2;
    tester.view.devicePixelRatio = 2;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(MaterialApp(home: Scaffold(body: EngReadableWidth(
        child: ListView(key: const Key('lista'), children: const [Card(child: ListTile(title: Text('Cliente')))])))));
    return tester.getRect(find.byKey(const Key('lista')));
  }

  testWidgets('tablet deitado: conteúdo centralizado com no máximo 960 dp', (tester) async {
    final r = await conteudo(tester, const Size(1280, 800));
    expect(r.width, 960);
    expect(r.left, (1280 - 960) / 2);
    expect(r.height, 800);
  });

  testWidgets('tablet em pé (800 dp) e celular (412 dp): ocupa a largura toda', (tester) async {
    expect((await conteudo(tester, const Size(800, 1280))).width, 800);
    expect((await conteudo(tester, const Size(412, 915))).width, 412);
  });
}
