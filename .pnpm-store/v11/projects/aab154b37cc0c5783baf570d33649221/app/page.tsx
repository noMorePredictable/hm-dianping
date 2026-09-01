import {
  Activity,
  ArrowUpRight,
  Crosshair,
  Radio,
  RefreshCw,
  ShieldCheck,
  SlidersHorizontal,
} from 'lucide-react';

import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';

const loadout = [
  { slot: '主武器', name: 'AKS-74U 突击步枪', market: '12,058', value: '12,305' },
  { slot: '枪口', name: 'DTK 制退器', market: '21,757', value: '25,847' },
  { slot: '前握把', name: 'X25U 斜侧战斗握把', market: '24,119', value: '25,620' },
  { slot: '瞄具', name: 'OSIGHT 微型瞄准镜', market: '18,477', value: '21,936' },
];

const tickers = [
  { name: 'M7 战斗步枪', category: '战斗步枪', price: '278,420', change: '+2.8%' },
  { name: '精英长枪管组合', category: '枪管', price: '86,775', change: '-6.4%' },
  { name: '共振二代前握把', category: '前握把', price: '49,210', change: '-1.9%' },
  { name: '全景红点瞄准镜', category: '瞄具', price: '31,680', change: '+4.1%' },
];

export default function Home() {
  return (
    <main className="min-h-screen overflow-x-hidden bg-background text-foreground">
      <header className="sticky top-0 z-20 border-b border-white/10 bg-[#0a0d0c]/92 backdrop-blur-xl">
        <div className="mx-auto flex h-16 max-w-[1500px] items-center gap-7 px-5 lg:px-8">
          <div className="flex items-center gap-3">
            <span className="delta-mark" aria-hidden="true" />
            <div className="leading-none">
              <p className="font-heading text-lg font-black tracking-[0.18em] text-white">DELTA / ARMORY</p>
              <p className="mt-1 text-[9px] tracking-[0.32em] text-[#8a9a90]">烽火地带 · 战术配装终端</p>
            </div>
          </div>

          <nav className="ml-auto hidden items-center gap-1 lg:flex" aria-label="主导航">
            <a className="nav-link nav-link-active" href="#loadout">卡战备</a>
            <a className="nav-link" href="#gunsmith">自由改枪</a>
            <a className="nav-link" href="#market">实时行情</a>
          </nav>

          <div className="ml-auto flex items-center gap-3 lg:ml-4">
            <div className="hidden items-center gap-2 text-[11px] text-[#aab5ae] sm:flex">
              <span className="relative flex h-2 w-2">
                <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-[#b6ff4a] opacity-60" />
                <span className="relative inline-flex h-2 w-2 rounded-full bg-[#b6ff4a]" />
              </span>
              行情连接正常
            </div>
            <Button variant="outline" className="border-white/15 bg-white/5 text-white hover:bg-white/10">
              <RefreshCw /> 同步行情
            </Button>
          </div>
        </div>
      </header>

      <section id="loadout" className="relative mx-auto max-w-[1500px] px-5 pb-10 pt-8 lg:px-8 lg:pt-12">
        <div className="pointer-events-none absolute inset-x-0 top-0 -z-0 h-[540px] tactical-grid opacity-45" />
        <div className="relative z-10 mb-8 flex flex-col justify-between gap-5 md:flex-row md:items-end">
          <div>
            <div className="mb-3 flex items-center gap-2 text-[11px] font-semibold tracking-[0.22em] text-primary">
              <Crosshair className="size-4" /> LOADOUT OPTIMIZER / 01
            </div>
            <h1 className="font-heading text-4xl font-black uppercase leading-[0.95] tracking-[-0.02em] text-white md:text-6xl">
              用最低成本<br /><span className="text-primary">突破战备门槛</span>
            </h1>
            <p className="mt-4 max-w-2xl text-sm leading-6 text-[#9aa49e]">
              按交易行最低价自动组合枪械与配件。输入预算与目标战备，终端会优先寻找差价最高的可用方案。
            </p>
          </div>
          <div className="flex gap-2">
            <Badge variant="outline" className="h-7 rounded-sm border-primary/40 bg-primary/10 px-3 text-primary">S10 裂变</Badge>
            <Badge variant="outline" className="h-7 rounded-sm border-white/15 bg-black/30 px-3 text-[#aab5ae]">数据每 10 分钟更新</Badge>
          </div>
        </div>

        <div className="relative z-10 grid gap-4 xl:grid-cols-[1.2fr_0.8fr]">
          <article className="ops-panel overflow-hidden">
            <div className="flex flex-wrap items-center justify-between gap-4 border-b border-white/10 px-5 py-4">
              <div className="flex items-center gap-3">
                <span className="flex size-9 items-center justify-center border border-primary/30 bg-primary/10 text-primary"><ShieldCheck /></span>
                <div>
                  <p className="text-xs font-bold tracking-[0.16em] text-white">绝密行动 · 航天基地</p>
                  <p className="mt-1 text-[10px] text-[#77827c]">最低成本策略 · 市场采购</p>
                </div>
              </div>
              <Button className="rounded-sm bg-primary px-4 font-black text-black hover:bg-primary/85">
                生成配装 <ArrowUpRight />
              </Button>
            </div>

            <div className="grid min-h-[390px] lg:grid-cols-[1fr_1.05fr]">
              <div className="relative flex min-h-[280px] items-center justify-center overflow-hidden border-b border-white/10 bg-[#0d1210] p-6 lg:border-b-0 lg:border-r">
                <div className="absolute left-5 top-5 z-10">
                  <p className="text-[10px] tracking-[0.18em] text-[#667169]">SELECTED PLATFORM</p>
                  <p className="mt-1 font-heading text-2xl font-black text-white">AKS-74U</p>
                  <p className="text-xs text-primary">突击步枪 / 5.45×39mm</p>
                </div>
                <span className="weapon-halo" aria-hidden="true" />
                <img
                  className="relative z-[1] mt-10 w-[92%] max-w-[520px] -rotate-2 object-contain drop-shadow-[0_28px_24px_rgba(0,0,0,.8)]"
                  src="https://playerhub.df.qq.com/playerhub/60004/object/18010000010.png"
                  alt="AKS-74U 突击步枪"
                />
                <span className="absolute bottom-5 left-5 text-[10px] tracking-[0.12em] text-[#5f6a63]">PLATFORM ID · DF-18010000010</span>
              </div>

              <div className="flex flex-col p-5 sm:p-6">
                <div className="grid grid-cols-2 gap-3">
                  <div className="metric-box">
                    <span>投入预算</span>
                    <strong>450,000</strong>
                    <small>哈夫币上限</small>
                  </div>
                  <div className="metric-box metric-box-active">
                    <span>目标战备</span>
                    <strong>600,000</strong>
                    <small>绝密门槛</small>
                  </div>
                </div>

                <div className="my-6">
                  <div className="mb-2 flex items-end justify-between">
                    <span className="text-xs text-[#87928b]">方案预估战备</span>
                    <span className="font-heading text-2xl font-black text-white">604,820</span>
                  </div>
                  <div className="h-2 overflow-hidden bg-white/5">
                    <div className="h-full w-[92%] bg-gradient-to-r from-[#7fb52e] to-primary shadow-[0_0_20px_rgba(182,255,74,.55)]" />
                  </div>
                  <div className="mt-2 flex justify-between text-[10px] text-[#69746d]">
                    <span>已满足门槛</span><span className="text-primary">+4,820 冗余</span>
                  </div>
                </div>

                <div className="mt-auto grid grid-cols-3 gap-px overflow-hidden border border-white/10 bg-white/10">
                  <div className="result-stat"><span>市场花费</span><strong>438,216</strong></div>
                  <div className="result-stat"><span>节省预算</span><strong className="text-primary">11,784</strong></div>
                  <div className="result-stat"><span>战备倍率</span><strong>1.38×</strong></div>
                </div>
              </div>
            </div>
          </article>

          <aside className="ops-panel flex min-h-[470px] flex-col">
            <div className="flex items-center justify-between border-b border-white/10 px-5 py-4">
              <div>
                <p className="text-xs font-bold tracking-[0.16em] text-white">最优采购清单</p>
                <p className="mt-1 text-[10px] text-[#77827c]">按实时市场价升序组合</p>
              </div>
              <SlidersHorizontal className="size-4 text-primary" />
            </div>
            <div className="divide-y divide-white/8 px-5">
              {loadout.map((item, index) => (
                <div key={item.name} className="grid grid-cols-[30px_1fr_auto] items-center gap-3 py-4">
                  <span className="flex size-7 items-center justify-center border border-white/10 bg-white/[.03] font-mono text-[10px] text-[#68736c]">0{index + 1}</span>
                  <div className="min-w-0">
                    <p className="truncate text-xs font-semibold text-[#dde4df]">{item.name}</p>
                    <p className="mt-1 text-[10px] text-[#68736c]">{item.slot} · 战备值 {item.value}</p>
                  </div>
                  <span className="font-mono text-xs font-bold text-white">{item.market}</span>
                </div>
              ))}
            </div>
            <div className="mt-auto border-t border-white/10 bg-primary/[.04] p-5">
              <div className="flex items-center gap-2 text-[10px] uppercase tracking-[0.18em] text-primary"><Activity className="size-3.5" /> Tactical insight</div>
              <p className="mt-2 text-xs leading-5 text-[#8f9a93]">当前组合以低价紫色配件抬升账面战备，保留 11,784 哈夫币预算用于弹药与药品。</p>
            </div>
          </aside>
        </div>
      </section>

      <section id="market" className="border-y border-white/10 bg-[#090c0b]">
        <div className="mx-auto max-w-[1500px] px-5 py-7 lg:px-8">
          <div className="mb-4 flex items-center justify-between">
            <div className="flex items-center gap-2 text-xs font-bold tracking-[0.15em] text-white"><Radio className="size-4 text-primary" /> 实时价格哨站</div>
            <span className="font-mono text-[10px] text-[#667169]">LAST SYNC 20:42:16 / UTC+8</span>
          </div>
          <div className="grid gap-px overflow-hidden border border-white/10 bg-white/10 sm:grid-cols-2 xl:grid-cols-4">
            {tickers.map((item) => (
              <div key={item.name} className="bg-[#0d1110] p-4 transition-colors hover:bg-[#111713]">
                <div className="flex items-start justify-between gap-4">
                  <div><p className="text-xs font-semibold text-white">{item.name}</p><p className="mt-1 text-[10px] text-[#69746d]">{item.category}</p></div>
                  <span className={item.change.startsWith('+') ? 'text-[#ff8f62]' : 'text-primary'}>{item.change}</span>
                </div>
                <p className="mt-5 font-heading text-2xl font-black text-[#e7ece9]">{item.price}<small className="ml-1 text-[10px] font-normal text-[#5f6963]">H</small></p>
              </div>
            ))}
          </div>
        </div>
      </section>
    </main>
  );
}
