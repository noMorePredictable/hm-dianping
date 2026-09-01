import type { Metadata } from 'next';
import './globals.css';

export const metadata: Metadata = {
  title: 'DELTA / ARMORY｜三角洲行动配枪终端',
  description: '三角洲行动实时物价、最低成本卡战备与自由改枪工具。',
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="zh-CN" className="dark">
      <body>{children}</body>
    </html>
  );
}
