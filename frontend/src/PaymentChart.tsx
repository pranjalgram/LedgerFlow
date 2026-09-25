import { Area, AreaChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import type { Row } from './components';

export default function PaymentChart({ data }: { data: Row[] }) {
  return <div className="chart"><ResponsiveContainer width="100%" height={220}><AreaChart data={data}><CartesianGrid strokeDasharray="3 3" vertical={false} /><XAxis dataKey="day" tickFormatter={value => String(value).slice(5)} /><YAxis allowDecimals={false} /><Tooltip /><Area dataKey="payments" name="Payments" stroke="#326b59" fill="#dce9d5" strokeWidth={2} isAnimationActive={false} /></AreaChart></ResponsiveContainer></div>;
}
