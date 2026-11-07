import duckdb, sys
DR=['hunger','sleep','pain','tedium']
def run(series, on, off, delta=0.3, crit=4.0, tmax=200, resume=10, hstar=0.18):
    focal=None; act=0; susp={}; opens=0; closes={'REG':0,'TO':0}; pre=0; focused=0
    for cyc,row in enumerate(series):
        u=[max(0,x-hstar) for x in row]
        for d in list(susp):
            if u[d]<=off: closes['REG']+=1; del susp[d]
            elif cyc>susp[d]['dl']: closes['TO']+=1; del susp[d]
        def open_best():
            nonlocal focal,act,opens
            best=None
            for d in range(4):
                if u[d]>=on and (best is None or u[d]>u[best]): best=d
            if best is not None:
                if best in susp: act=susp.pop(best)['act']
                else: act=0; opens+=1
                focal=best
        if focal is not None:
            act+=1; fu=u[focal]
            if fu<=off: closes['REG']+=1; focal=None
            elif act>tmax: closes['TO']+=1; focal=None; continue
            else:
                emer=fu<crit; ch=None
                for d in range(4):
                    if d==focal: continue
                    if (emer and u[d]>=crit) or u[d]>fu+delta:
                        if ch is None or u[d]>u[ch]: ch=d
                if ch is not None:
                    susp[focal]={'dl':cyc+resume,'act':act}; pre+=1; focal=None; open_best()
                if focal is not None: focused+=1
                continue
        open_best()
        if focal is not None: focused+=1
    return opens,closes['REG'],closes['TO'],pre,focused/len(series)
if __name__=='__main__':
    c=duckdb.connect(); d='ml/data_p107_pilot_gate/'
    grid=[(2.0,0.5),(1.0,0.3),(0.8,0.3),(0.6,0.2),(0.5,0.2),(0.4,0.15),(0.3,0.1)]
    for k in ['a1','a2']:
        rows=c.sql(f"SELECT creature_key,final_hunger,final_sleep,final_pain,final_tedium FROM '{d}{k}/trial_1/drives.parquet' ORDER BY creature_key,time").fetchall()
        by={}
        for r in rows: by.setdefault(r[0],[]).append(r[1:])
        print(k,'(opens, REGULATED, TIMED_OUT, preempts, focused share) per creature mean')
        for on,off in grid:
            res=[run(s,on,off) for s in by.values()]
            print(f' on={on} off={off}:', [round(sum(x[i] for x in res)/len(res),3) for i in range(5)], 'min opens',min(x[0] for x in res))
